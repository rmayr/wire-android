package com.waz.zclient.convExport.fragments;

import java.text.DateFormat
import java.util.Date
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}

import android.app.{Activity, DatePickerDialog, TimePickerDialog}
import android.os.{Bundle, Environment}
import android.content.Intent
import android.provider.DocumentsContract
import android.view.View.OnClickListener
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.{Button, CompoundButton, DatePicker, LinearLayout, ProgressBar, RelativeLayout, Switch, TextView, TimePicker}
import com.google.android.material.textfield.TextInputLayout
import com.waz.model.RemoteInstant
import com.waz.utils.returningF
import com.waz.zclient.convExport.{ExportController, ExportProgress}
import com.waz.zclient.{FragmentHelper, R}
import io.reactivex.functions.Consumer
import android.icu.util.Calendar
import com.waz.zclient.log.LogUI.{showString, verbose}
import com.waz.zclient.log.LogUI._
import io.reactivex.disposables.Disposable

class ExportConfigurationFragment extends FragmentHelper {
  private val SELECT_FILE_REQUEST = 1
  private lazy val exportController = inject[ExportController]

  private lazy val exportButton=view[Button](R.id.b__export_start).get
  private lazy val exportCancelButton=view[Button](R.id.export_cancel).get
  private lazy val exportIncludeMediaSwitch=view[Switch](R.id.export_include_media).get
  private lazy val exportIncludeProfilePicturesSwitch=view[Switch](R.id.export_include_profile_pictures).get
  private lazy val exportIncludeHtmlSwitch=view[Switch](R.id.export_include_html).get
  private lazy val exportLimitFromSwitch=view[Switch](R.id.export_limit_from).get
  private lazy val exportLimitToSwitch=view[Switch](R.id.export_limit_to).get
  private lazy val filePathInput=view[TextInputLayout](R.id.b__export_file_input).get
  private lazy val dateFromInput=view[Button](R.id.export_date_from).get
  private lazy val dateToInput=view[Button](R.id.export_date_to).get

  private lazy val exportLoadingIndicator=view[LinearLayout](R.id.export_loading_indicator).get
  private lazy val exportProgressState=view[TextView](R.id.export_progress_state).get

  private lazy val exportProgressConversations=view[ProgressBar](R.id.export_progress_conversations).get
  private lazy val exportProgressConversationsCurrent=view[TextView](R.id.export_progress_conversations_current).get
  private lazy val exportProgressConversationsTotal=view[TextView](R.id.export_progress_conversations_total).get

  private lazy val exportProgressUsers=view[ProgressBar](R.id.export_progress_users).get
  private lazy val exportProgressUsersCurrent=view[TextView](R.id.export_progress_users_current).get
  private lazy val exportProgressUsersTotal=view[TextView](R.id.export_progress_users_total).get

  private lazy val exportProgressMessagesContainer=view[RelativeLayout](R.id.export_progress_messages_container).get
  private lazy val exportProgressMessages=view[ProgressBar](R.id.export_progress_messages).get
  private lazy val exportProgressMessagesCurrent=view[TextView](R.id.export_progress_messages_current).get
  private lazy val exportProgressMessagesTotal=view[TextView](R.id.export_progress_messages_total).get

  private lazy val exportProgressAssets=view[ProgressBar](R.id.export_progress_asset).get

  private var dateFrom: Option[RemoteInstant] = None
  private var dateTo: Option[RemoteInstant] = None
  private var currentExportSubscriber: Disposable = _
  private var currentProgressService: Option[ScheduledExecutorService] = None

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View =
    inflater.inflate(R.layout.fragment_export_configuration, container, false)

  override def onDestroyView(): Unit = {
    if(currentExportSubscriber!=null) currentExportSubscriber.dispose()
    currentProgressService.foreach(s=>s.shutdown())
    super.onDestroyView()
  }

  override def onViewCreated(view: View, savedInstanceState: Bundle): Unit = {
    super.onViewCreated(view, savedInstanceState)

    currentExportSubscriber=exportController.currentExport.subscribe(new Consumer[Option[ExportProgress]] {
      override def accept(progressOption: Option[ExportProgress]): Unit = {
        verbose(l"Export: PROGRESS UPDATED")
        getContext.asInstanceOf[Activity].runOnUiThread(new Runnable {
          override def run(): Unit = {
            if(progressOption.nonEmpty){
              verbose(l"Export: Progress not empty")
              filePathInput.getEditText.setText(exportController.exportFile.get.toString)
              switchToRunningExport(true)
              if(currentProgressService.nonEmpty) currentProgressService.get.shutdown()
              currentProgressService=Some(Executors.newSingleThreadScheduledExecutor())
              val exportProgress=progressOption.get
              currentProgressService.get.scheduleAtFixedRate(new Runnable {
                override def run(): Unit = {
                  getContext.asInstanceOf[Activity].runOnUiThread(new Runnable {
                    override def run(): Unit = {
                      exportProgressState.setText(exportProgress.currentState.toString)

                      exportProgressConversations.setProgress(((exportProgress.conversationsDone.toFloat / exportProgress.conversationsTotal) * 100).toInt)
                      exportProgressConversationsTotal.setText(exportProgress.conversationsTotal.toString)
                      exportProgressConversationsCurrent.setText(exportProgress.conversationsDone.toString)

                      exportProgressUsers.setProgress(((exportProgress.usersDone.toFloat / exportProgress.usersTotal) * 100).toInt)
                      exportProgressUsersTotal.setText(exportProgress.usersTotal.toString)
                      exportProgressUsersCurrent.setText(exportProgress.usersDone.toString)

                      if (exportProgress.messagesCurrentConversationTotal >= 0) {
                        exportProgressMessagesContainer.setVisibility(View.VISIBLE)
                        exportProgressMessages.setProgress(((exportProgress.messagesCurrentConversationDone.toFloat / exportProgress.messagesCurrentConversationTotal) * 100).toInt)
                        exportProgressMessagesTotal.setText(exportProgress.messagesCurrentConversationTotal.toString)
                        exportProgressMessagesCurrent.setText(exportProgress.messagesCurrentConversationDone.toString)
                      } else {
                        exportProgressMessagesContainer.setVisibility(View.INVISIBLE)
                      }

                      if (exportProgress.assetDownloadTotal >= 0 || exportProgress.assetDownloadDone >= 0) {
                        exportProgressAssets.setVisibility(View.VISIBLE)
                        if(exportProgress.assetDownloadTotal>=0) {
                          exportProgressAssets.setIndeterminate(false)
                          exportProgressAssets.setProgress(((exportProgress.assetDownloadDone.toFloat / exportProgress.assetDownloadTotal) * 100).toInt)
                        }else{
                          exportProgressAssets.setIndeterminate(true)
                        }
                      } else {
                        exportProgressAssets.setVisibility(View.INVISIBLE)
                      }
                    }})
                }
              },0, 200, TimeUnit.MILLISECONDS)
              verbose(l"Export: Progress schedule running")
            }else{
              verbose(l"Export: Progress done")
              //if(exportController.exportFile.nonEmpty && filePathInput.getEditText.getText.toString.equals(exportController.exportFile.get.toString)) enableExportButton()
              switchToRunningExport(false)
              currentProgressService.foreach(s=>s.shutdown())
              currentProgressService=None
              exportCancelButton.setEnabled(true)
            }
          }
        })
      }
    }, new Consumer[Throwable] {
      override def accept(t: Throwable): Unit = {
        verbose(l"Export: Error - ${showString(t.toString)}")
        t.printStackTrace()
      }
    })

    exportButton.setEnabled(false)
    exportCancelButton.setOnClickListener(new OnClickListener {
      override def onClick(v: View): Unit = {
        exportController.cancelExport = true
        exportCancelButton.setEnabled(false)
        verbose(l"Export: CANCEL EXPORT - ${showString(exportController.cancelExport.toString)}")
      }
    })

    /*
    exportButton.setText("ADD DEBUG MESSAGES")
    exportButton.setEnabled(true)
    exportButton.setOnClickListener(new View.OnClickListener {
      override def onClick(v: View): Unit = {
        exportLoadingIndicator.setVisibility(View.VISIBLE)
        exportController.generateTextMessages(()=>{
          getContext.asInstanceOf[Activity].runOnUiThread(new Runnable {
            override def run(): Unit = {
              exportLoadingIndicator.setVisibility(View.GONE)
              Toast.makeText(getContext,WireApplication.APP_INSTANCE.getApplicationContext.getString(R.string.export_done),Toast.LENGTH_LONG)
            }
          })
        })
      }
    })
    exportButton.setText(R.string.start_export)
     */

    exportIncludeMediaSwitch.setChecked(exportController.exportFiles)
    exportIncludeMediaSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener() {
      def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit = {exportController.exportFiles=isChecked}
    })

    exportIncludeProfilePicturesSwitch.setChecked(exportController.exportProfilePictures)
    exportIncludeProfilePicturesSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener() {
      def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit = {exportController.exportProfilePictures=isChecked}
    })

    exportIncludeHtmlSwitch.setChecked(exportController.includeHtml)
    exportIncludeHtmlSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener() {
        def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit = {exportController.includeHtml=isChecked}
      })

    dateFrom=exportController.timeFrom
    exportLimitFromSwitch.setChecked(dateFrom.nonEmpty)
    exportLimitFromSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener() {
      def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit = {
        if(isChecked)
          exportController.timeFrom=dateFrom
        else
          exportController.timeFrom=None
      }
    })
    dateFromInput.setOnClickListener(new OnClickListener {
      override def onClick(v: View): Unit = {
        showDateTimePicker(dateFrom,r=>{
          dateFrom=Some(r)
          val date = new Date()
          date.setTime(r.toEpochMilli)
          val format=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
          dateFromInput.setText(format.format(date))
          if(exportLimitFromSwitch.isChecked)
            exportController.timeFrom=dateFrom;
        })
      }
    })

    dateTo=exportController.timeTo
    exportLimitToSwitch.setChecked(dateTo.nonEmpty)
    exportLimitToSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener() {
      def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit = {
        if(isChecked)
          exportController.timeTo=dateTo
        else
          exportController.timeTo=None
      }
    })
    dateToInput.setOnClickListener(new OnClickListener {
      override def onClick(v: View): Unit = {
        showDateTimePicker(dateTo,r=>{
          dateTo=Some(r)
          val date = new Date()
          date.setTime(r.toEpochMilli)
          val format=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
          dateToInput.setText(format.format(date))
          if(exportLimitToSwitch.isChecked)
            exportController.timeTo=dateTo
        })
      }
    })

    filePathInput.getEditText.setEnabled(false)
    returningF(findById(R.id.b__export_file_select)){ b: Button =>
      b.setOnClickListener(new View.OnClickListener {
        override def onClick(v: View): Unit = {
          selectFile()
        }
      })
    }
  }

  def switchToRunningExport(running: Boolean): Unit = {
    if (running) {
      exportLoadingIndicator.setVisibility(View.VISIBLE)
    } else {
      exportLoadingIndicator.setVisibility(View.GONE)
    }
  }

  def enableExportButton(): Unit = {
    exportButton.setEnabled(true)
    exportButton.setOnClickListener(new View.OnClickListener {
      override def onClick(v: View): Unit = {
        exportController.`export`()
      }
    })
  }

  def showDateTimePicker(time: Option[RemoteInstant], callback: RemoteInstant => Unit): Unit = {
    val currentDate = Calendar.getInstance
    val date = Calendar.getInstance
    if(time.nonEmpty)
      date.setTimeInMillis(time.get.toEpochMilli)
    new DatePickerDialog(getContext, new DatePickerDialog.OnDateSetListener() {
      override def onDateSet(view: DatePicker, year: Int, monthOfYear: Int, dayOfMonth: Int): Unit = {
        date.set(year, monthOfYear, dayOfMonth)
        new TimePickerDialog(getContext, new TimePickerDialog.OnTimeSetListener() {
          def onTimeSet(view: TimePicker, hourOfDay: Int, minute: Int): Unit = {
            date.set(Calendar.HOUR_OF_DAY, hourOfDay)
            date.set(Calendar.MINUTE, minute)
            callback(RemoteInstant.ofEpochMilli(date.getTimeInMillis))
          }
        }, currentDate.get(Calendar.HOUR_OF_DAY), currentDate.get(Calendar.MINUTE), false).show()
      }
    }, currentDate.get(Calendar.YEAR), currentDate.get(Calendar.MONTH), currentDate.get(Calendar.DATE)).show()
  }

  def selectFile(): Unit = {
    val intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
    intent.addCategory(Intent.CATEGORY_OPENABLE)
    intent.setType("application/zip")
    intent.putExtra(Intent.EXTRA_TITLE, "chatexport.zip")
    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Environment.DIRECTORY_DOCUMENTS)
    startActivityForResult(intent, SELECT_FILE_REQUEST)
  }

  override def onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent): Unit = {
    if (requestCode == SELECT_FILE_REQUEST && resultCode == Activity.RESULT_OK) {
      if (resultData != null) {
        exportController.exportFile = Some(resultData.getData)
        filePathInput.getEditText.setText(exportController.exportFile.get.toString)
        enableExportButton()
      }
    }
  }
}

object ExportConfigurationFragment {
    val Tag: String = getClass.getSimpleName
}
