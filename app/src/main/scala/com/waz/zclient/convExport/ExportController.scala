package com.waz.zclient.convExport

import java.net.URI

import android.content.{ContentResolver, Context}
import android.net.Uri
import com.waz.content.ConversationStorage
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model.{ConvId, RemoteInstant}
import com.waz.service.ZMessaging
import com.waz.service.assets.{Content, ContentForUpload}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.log.LogUI.{showString, verbose, _}
import com.waz.zclient.messages.UsersController
import com.waz.zclient.{Injectable, Injector, WireApplication}
import com.wire.signals.{EventContext, EventStream, Signal, SourceStream}

import scala.util.Random
import com.waz.zclient.R
import io.reactivex.subjects.BehaviorSubject

class ExportController(implicit injector: Injector, context: Context, ec: EventContext)
  extends Injectable with DerivedLogTag {

  val zms: Signal[ZMessaging] = inject[Signal[ZMessaging]]
  val convController: ConversationController = inject[ConversationController]
  lazy val convStorage: ConversationStorage = zms.currentValue.get.convsStorage
  val usersController: UsersController = inject[UsersController]
  val onShowExport: SourceStream[Option[String]] = EventStream[Option[String]]()

  val currentExport: BehaviorSubject[Option[ExportProgress]] = BehaviorSubject.createDefault(None)
  var exportFile: Option[Uri] = None
  var timeFrom: Option[RemoteInstant] = None
  var timeTo: Option[RemoteInstant] = None
  var exportFiles = true
  var exportProfilePictures = true
  var includeHtml = true
  var cancelExport = false
  var exportConvIds: Option[IndexedSeq[ConvId]] = None

  def export() : Unit = {
    if(exportConvIds.isEmpty){
      exportConvIds=Some(IndexedSeq(convController.getCurrentConvId))
    }
    currentExport.synchronized{
      new Thread(new Runnable {
        override def run(): Unit = {
          if(exportConvIds.nonEmpty){
            val newExport = new ExportConverter(ExportController.this)
            currentExport.onNext(Some(newExport.getExportProgress))
            try{
              newExport.export(exportConvIds.get)
            }catch{
              case e: Exception => e.printStackTrace()
            }finally{
              currentExport.onNext(None)
              cancelExport = false
            }
          }
        }
      }).start()
    }
  }
  // DEBUG CODE ONLY
  def generateTextMessages(callbackFinished: () => Unit): Unit = {
    new Thread(new Runnable {
      override def run(): Unit = {
        val words = Seq("this", "are", "really", "long", "sentences", "to", "generate", "text", "for", "testing", "purposes", "only")
        val random = Random
        /*
        for (i <- 1 to 10000) {
          val str = (for (_ <- 1 to 100) yield random.nextInt(words.length)).map(ind => words(ind)).mkString(" ")
          verbose(l"TestMessage: ${showString(i.toString)} => ${showString(str)}")
          convController.sendMessage(str)
        }
         */
        val uri = new Uri.Builder()
          .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
          .authority(WireApplication.APP_INSTANCE.getResources.getResourcePackageName(R.raw.export_html_viewer))
          .appendPath(WireApplication.APP_INSTANCE.getResources.getResourceTypeName(R.raw.export_html_viewer))
          .appendPath(WireApplication.APP_INSTANCE.getResources.getResourceEntryName(R.raw.export_html_viewer))
          .build()
        for (i <- 1 to 100) {
          val content = ContentForUpload("html-viewer"+i+".zip",  Content.Uri(URI.create(uri.toString)))
          verbose(l"TestMessageC: ${showString(i.toString)} => ${showString(URI.create(uri.toString).toString)}")
          convController.sendAssetMessage(content)
        }
        callbackFinished()
      }
    }).start()
  }
  // DEBUG CODE ONLY END
}

object ExportController {

}
