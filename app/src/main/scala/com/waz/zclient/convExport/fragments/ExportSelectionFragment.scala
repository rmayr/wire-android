package com.waz.zclient.convExport.fragments

import android.os.Bundle
import android.view.View.OnClickListener
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.{BaseAdapter, Button, CheckBox, CompoundButton, LinearLayout, ListView, TextView}
import com.waz.model.ConversationData
import com.waz.zclient.convExport.ExportController
import com.waz.zclient.{FragmentHelper, R}

class ExportSelectionFragment extends FragmentHelper {
  private lazy val exportController = inject[ExportController]
  private lazy val exportSelectionListView=view[ListView](R.id.export_selection).get
  private lazy val exportConfirmSelection=view[Button](R.id.export_confirm_selection).get


  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View =
    inflater.inflate(R.layout.fragment_export_configuration, container, false)

  override def onViewCreated(view: View, savedInstanceState: Bundle): Unit = {
    super.onViewCreated(view, savedInstanceState)
    val conversationList = exportController.convStorage.contents.currentValue.getOrElse(Map.empty).values.toIndexedSeq.map(c=>Doublet(c, false))
    exportConfirmSelection.setOnClickListener(new OnClickListener {
      override def onClick(v: View): Unit = {
        exportController.exportConvIds=Some(conversationList.filter(d=>d._2).map(d=>d._1.id))
        exportController.onShowExport ! Some(ExportConfigurationFragment.Tag)
      }
    })
    exportSelectionListView.setAdapter(new BaseAdapter {
      override def getCount: Int = {
        conversationList.size
      }

      override def getItem(position: Int): ConversationData = {
        conversationList(position)._1
      }

      override def getItemId(position: Int): Long = {
        position
      }

      override def getView(position: Int, convertView: View, parent: ViewGroup): View = {
        var view=convertView
        if(view==null){
          val root = new LinearLayout(getContext)
          root.setOrientation(LinearLayout.HORIZONTAL)
          val tvName=new TextView(getContext)
          tvName.setId(1)
          val tvCheck=new CheckBox(getContext)
          tvCheck.setId(2)
          root.addView(tvName)
          view=root
        }
        view.findViewById[CheckBox](1).setChecked(conversationList(position)._2)
        view.findViewById[CheckBox](1).setOnCheckedChangeListener(new OnCheckedChangeListener {
          override def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit = {
            conversationList(position)._2=isChecked
          }
        })
        view.findViewById[TextView](1).setText(getItem(position).name.map(n=>n.str).getOrElse(getItem(position).id.str))
        view
      }
    })
  }

  case class Doublet[ConversationData, Boolean](var _1: ConversationData, var _2: Boolean){}

}

object ExportSelectionFragment {
  val Tag: String = getClass.getSimpleName
}
