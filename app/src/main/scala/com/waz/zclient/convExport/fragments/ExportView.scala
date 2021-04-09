package com.waz.zclient.convExport.fragments

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.zclient.ViewHelper
import com.waz.zclient.convExport.ExportController
import com.waz.zclient.utils.BackStackKey
import com.wire.signals.Signal
import com.waz.zclient.R

import scala.concurrent.blocking
import scala.concurrent.{ExecutionContext, Future}

class ExportView(context: Context, attrs: AttributeSet, style: Int)
  extends LinearLayout(context, attrs, style)
    with ViewHelper
    with DerivedLogTag{
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  private lazy val exportController = inject[ExportController]

  def loadView(page: String): Unit = {
    page match {
      case ExportConfigurationFragment.Tag =>
        load(new ExportConfigurationFragment)
      case ExportSelectionFragment.Tag if exportController.currentExport.getValue.isEmpty =>
        load(new ExportSelectionFragment)
      case ExportSelectionFragment.Tag if exportController.currentExport.getValue.nonEmpty=>
        load(new ExportConfigurationFragment)
      case _ =>
        load(new ExportSelectionFragment)
    }
  }
  private def load(fragment: Fragment) = {

  }
}

case class ExportKey(args: Bundle = new Bundle()) extends BackStackKey(args){
  private var view: Option[View] = None
  override def nameId: Int = R.string.pref_account_export_title

  override def layoutId: Int = R.layout.preferences_conversation_export

  override def onViewAttached(v: View): Unit = {
    view.synchronized{
      view=Some(v)
      view.notifyAll()
    }
  }

  def getView(): Signal[View] = {
    view.synchronized {
      if(view.nonEmpty) return Signal.const(view.get)
    }
    Signal.from(Future{
      blocking{
        view.synchronized{
          while(view.isEmpty){
            view.wait()
          }
          return Signal.const(view.get)
        }
      }
    }(ExecutionContext.global))
  }

  override def onViewDetached(): Unit = {}
}
