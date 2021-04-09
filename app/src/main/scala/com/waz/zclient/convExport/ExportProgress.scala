package com.waz.zclient.convExport

class ExportProgress(exportConverter: ExportConverter) {
  var conversationsTotal: Int = 0
  var conversationsDone: Int = 0
  var usersTotal: Int = 0
  var usersDone: Int = 0
  var messagesCurrentConversationTotal: Int = -1
  var messagesCurrentConversationDone: Int = 0
  var assetDownloadTotal: Long = -1
  var assetDownloadDone: Long = -1
  var currentState: ExportProgressState.Value = ExportProgressState.INIT
  def getExportConverter: ExportConverter = exportConverter
}

object ExportProgressState extends Enumeration{
  type ExportProgressState = Value
  val INIT, STARTED, CONVERSATIONS, USERS, XML, HTML, DONE, CANCELED = Value
}
