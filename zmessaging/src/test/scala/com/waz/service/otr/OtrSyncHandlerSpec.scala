/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.otr

import com.waz.api.{ErrorType, Verification}
import com.waz.api.impl.ErrorResponse
import com.waz.content.{ConversationStorage, MembersStorage, OtrClientsStorage, UsersStorage}
import com.waz.model.ConversationData.LegalHoldStatus
import com.waz.model.GenericMessage.TextMessage
import com.waz.model.otr.{Client, ClientId}
import com.waz.model._
import com.waz.service.conversation.ConversationsService
import com.waz.service.push.PushService
import com.waz.service.{ErrorsService, SearchKey, UserService}
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.SyncResult
import com.waz.sync.client.OtrClient.{ClientMismatch, EncryptedContent, MessageResponse}
import com.waz.sync.client.{MessagesClient, OtrClient}
import com.waz.sync.otr.OtrSyncHandler.{OtrMessage, TargetRecipients}
import com.waz.sync.otr.{OtrClientsSyncHandler, OtrSyncHandlerImpl}
import com.wire.signals.CancellableFuture

import scala.concurrent.Future

class OtrSyncHandlerSpec extends AndroidFreeSpec {

  val teamId             = Some(TeamId())
  val selfClientId       = ClientId("client-id")
  val otrClient          = mock[OtrClient]
  val msgClient          = mock[MessagesClient]
  val service            = mock[OtrService]
  val convStorage        = mock[ConversationStorage]
  val convsService       = mock[ConversationsService]
  val users              = mock[UserService]
  val members            = mock[MembersStorage]
  val errors             = mock[ErrorsService]
  val clientsSyncHandler = mock[OtrClientsSyncHandler]
  val push               = mock[PushService]
  val usersStorage       = mock[UsersStorage]
  val clientsStorage     = mock[OtrClientsStorage]

  def getSyncHandler: OtrSyncHandlerImpl = {
    (push.waitProcessing _).expects().anyNumberOfTimes.returning(Future.successful({}))
    new OtrSyncHandlerImpl(
      teamId,
      selfClientId,
      otrClient,
      msgClient,
      service,
      convsService,
      convStorage,
      users,
      members,
      errors,
      clientsSyncHandler,
      push,
      usersStorage,
      clientsStorage
    )
  }

  def createTextMessage(content: String): GenericMessage =
    TextMessage(content, Nil, expectsReadConfirmation = false, Messages.LegalHoldStatus.UNKNOWN)

  feature("Post OTR message") {

    def setUpExpectationsForSucessfulEncryptAndSend(legalHoldStatus: LegalHoldStatus = LegalHoldStatus.Disabled): (ConvId, GenericMessage) = {
      // Given
      val conv = ConversationData(ConvId("conv-id"), RConvId("r-conv-id"), legalHoldStatus = legalHoldStatus)
      val msg = createTextMessage("content")

      val otherUser = UserId("other-user-id")
      val otherUsersClient = ClientId("other-user-client-id")
      val recipients = Map(account1Id -> Set.empty[ClientId], otherUser -> Set(otherUsersClient))

      val content = "content".getBytes
      val encryptedContent = EncryptedContent(Map(otherUser -> Map(otherUsersClient -> content)))

      // Expectations
      (convStorage.get _)
        .expects(conv.id)
        .returning(Future.successful(Some(conv)))

      (members.getActiveUsers _)
        .expects(conv.id)
        .returning(Future.successful(Seq(account1Id, otherUser)))

      (clientsStorage.getClients _)
        .expects(otherUser)
        .once()
        .returning(Future.successful(Seq(Client(otherUsersClient, ""))))

      (clientsStorage.getClients _)
        .expects(account1Id)
        .once()
        .returning(Future.successful(Seq(Client(selfClientId, ""))))

      (service.encryptMessage _)
        .expects( msg, recipients, *, EncryptedContent.Empty)
        .returning(Future.successful(encryptedContent))

      (msgClient.postMessage _)
        .expects(conv.remoteId, OtrMessage(selfClientId, encryptedContent), *)
        .returning(CancellableFuture.successful(Right(MessageResponse.Success(ClientMismatch(time = RemoteInstant.Epoch)))))

      (service.deleteClients _)
        .expects(Map.empty[UserId, Seq[ClientId]])
        .returning(Future.successful({}))

      (convsService.addUnexpectedMembersToConv _)
        .expects(conv.id, Set.empty[UserId])
        .returning(Future.successful({}))

      (conv.id, msg)
    }

    scenario("Encrypt and send message with no errors") {
      // Given
      val syncHandler = getSyncHandler

      // Expectations
      val (convId, msg) = setUpExpectationsForSucessfulEncryptAndSend()

      // When
      result(syncHandler.postOtrMessage(convId, msg, isHidden = false))
    }

    scenario("Encrypt and send hidden message in unapproved legal hold conversation with no errors") {
      // Given
      val syncHandler = getSyncHandler

      // Expectations
      val (convId, msg) = setUpExpectationsForSucessfulEncryptAndSend(LegalHoldStatus.PendingApproval)

      // When
      result(syncHandler.postOtrMessage(convId, msg, isHidden = true))
    }

    scenario("Can't encrypt or send message in unapproved legal hold conversation") {
      // Given
      val syncHandler = getSyncHandler
      val conv = ConversationData(ConvId("conv-id"), RConvId("r-conv-id"), legalHoldStatus = LegalHoldStatus.PendingApproval)

      // Send calling message to avoid triggering errors service
      val message = GenericMessage(Uid(), GenericContent.Calling("msg"))

      // Expectations
      (convStorage.get _)
        .expects(conv.id)
        .returning(Future.successful(Some(conv)))

      (errors.addUnapprovedLegalHoldStatusError _)
        .expects(conv.id, MessageId(message.proto.getMessageId))
        .once()
        .returning(Future.successful(ErrorData(Uid(), ErrorType.CANNOT_SEND_MESSAGE_TO_UNAPPROVED_LEGAL_HOLD_CONVERSATION)))

      val actualResult = result(syncHandler.postOtrMessage(conv.id, message, isHidden = false))

      // Then
      actualResult shouldEqual Left(ErrorResponse.UnapprovedLegalHold)
    }

    scenario("Can't encrypt or send message in unverified conversation") {
      // Given
      val syncHandler = getSyncHandler
      val conv = ConversationData(ConvId("conv-id"), RConvId("r-conv-id"), verified = Verification.UNVERIFIED)

      // Send calling message to avoid triggering errors service
      val message = GenericMessage(Uid(), GenericContent.Calling("msg"))

      // Expectations
      (convStorage.get _)
        .expects(conv.id)
        .returning(Future.successful(Some(conv)))

      val actualResult = result(syncHandler.postOtrMessage(conv.id, message, isHidden = false))

      // Then
      actualResult shouldEqual Left(ErrorResponse.Unverified)
    }

    scenario("Unexpected users and/or clients in missing response should be updated and added to members, and previously encrypted content should be updated") {
      // Given
      val syncHandler = getSyncHandler
      val conv = ConversationData(ConvId("conv-id"), RConvId("r-conv-id"))
      val msg = createTextMessage("content")

      val otherUser        = UserId("other-user-id")
      val otherUsersClient = ClientId("other-user-client-1")
      val content          = "content".getBytes

      val encryptedContent1 = EncryptedContent(Map(otherUser -> Map(otherUsersClient -> content)))

      val missingUser       = UserId("missing-user-id")
      val missingUserData   = UserData(missingUser, domain = Some("domain"), name = Name("missing user"), searchKey = SearchKey.simple("missing user"))
      val missingUserClient = ClientId("missing-user-client-1")
      val contentForMissing = "content".getBytes
      val missing = Map(missingUser -> Seq(missingUserClient))

      val encryptedContent2 = EncryptedContent(
        encryptedContent1.content ++
          Map(missingUser -> Map(missingUserClient -> contentForMissing))
      )

      // Expectations
      (convStorage.get _)
        .expects(conv.id)
        .twice()
        .returning(Future.successful(Some(conv)))

      var callsToActiveMembers = 0
      (members.getActiveUsers _)
        .expects(conv.id)
        .twice()
        .onCall { _: ConvId =>
          Future.successful {
            callsToActiveMembers += 1
            callsToActiveMembers match {
              case 1 =>
                Seq(account1Id, otherUser)
              case 2 =>
                Seq(account1Id, otherUser, missingUser)
              case _ => fail("Unexpected number of calls to postMessage")
            }
          }
        }

      (clientsStorage.getClients _)
        .expects(otherUser)
        .twice()
        .returning(Future.successful(Seq(Client(otherUsersClient, ""))))

      (clientsStorage.getClients _)
        .expects(account1Id)
        .twice()
        .returning(Future.successful(Seq(Client(selfClientId, ""))))

      (clientsStorage.getClients _)
        .expects(missingUser)
        .once()
        .returning(Future.successful(Seq(Client(missingUserClient, ""))))

      var callsToEncrypt = 0
      (service.encryptMessage _)
        .expects(msg, *, *, *)
        .twice()
        .onCall { (_, recipients, _, previous) =>
          callsToEncrypt += 1
          callsToEncrypt match {
            case 1 =>
              recipients shouldEqual Map(account1Id -> Set.empty[ClientId], otherUser -> Set(otherUsersClient))
              previous shouldEqual EncryptedContent.Empty
              Future.successful(encryptedContent1)
            case 2 =>
              recipients shouldEqual Map(account1Id -> Set.empty[ClientId], otherUser -> Set(otherUsersClient), missingUser -> Set(missingUserClient))
              previous shouldEqual encryptedContent1
              Future.successful(encryptedContent2)
          }
        }

      var callsToPostMessage = 0
      (msgClient.postMessage _)
        .expects(conv.remoteId, *, *)
        .twice()
        .onCall { (_, msg, ignoreMissing: Boolean) =>
          CancellableFuture.successful(Right {
            callsToPostMessage += 1
            ignoreMissing shouldEqual false
            msg.sender shouldEqual selfClientId
            callsToPostMessage match {
              case 1 =>
                msg.recipients shouldEqual encryptedContent1
                MessageResponse.Failure(ClientMismatch(missing = missing, time = RemoteInstant.Max))
              case 2 =>
                msg.recipients shouldEqual encryptedContent2
                MessageResponse.Success(ClientMismatch(time = RemoteInstant.Max))
              case _ => fail("Unexpected number of calls to postMessage")
            }
          })
        }

      (service.deleteClients _)
        .expects(Map.empty[UserId, Seq[ClientId]])
        .twice()
        .returning(Future.successful({}))

      var callsToAddUnexpectedMembers = 0
      (convsService.addUnexpectedMembersToConv _)
        .expects(conv.id, *)
        .twice()
        .onCall { (_, us) =>
          callsToAddUnexpectedMembers += 1
          callsToAddUnexpectedMembers match {
            case 1 => us.size shouldEqual 1
              Future.successful(Some(SyncId()))
            case _ => us.size shouldEqual 0
              Future.successful(Option.empty[SyncId])
          }
        }

      (usersStorage.listAll _)
        .expects(Set(missingUser))
        .once()
        .returning(Future.successful(Vector(missingUserData)))

      (clientsSyncHandler.syncClients(_ : Set[QualifiedId]))
        .expects(Set(missingUserData.qualifiedId.get))
        .returning(Future.successful(SyncResult.Success))

      // When
      result(syncHandler.postOtrMessage(conv.id, msg, isHidden = false))
    }

    scenario("Target message to specific clients") {
      // Given
      val syncHandler = getSyncHandler
      val conv = ConversationData(ConvId("conv-id"), RConvId("r-conv-id"))
      val msg = createTextMessage("content")

      val otherUser1 = UserId("other-user-1")
      val otherUser2 = UserId("other-user-2")

      val otherClient1 = ClientId("other-client-1")
      val otherClient2 = ClientId("other-client-2")

      val content = "content".getBytes
      val encryptedContent = EncryptedContent(Map(otherUser1 -> Map(otherClient1 -> content, otherClient2 -> content)))

      val targetRecipients = Map(otherUser1 -> Set(otherClient1), otherUser2 -> Set(otherClient2))

      // Expectations
      (convStorage.get _)
        .expects(conv.id)
        .returning(Future.successful(Some(conv)))

      (service.encryptMessage _)
        .expects(msg, Map(otherUser1 -> Set(otherClient1), otherUser2 -> Set(otherClient2)), *, EncryptedContent.Empty)
        .returning(Future.successful(encryptedContent))

      (msgClient.postMessage _)
        .expects(conv.remoteId, OtrMessage(selfClientId, encryptedContent), true)
        .returning(CancellableFuture.successful(Right(MessageResponse.Success(ClientMismatch(time = RemoteInstant.Epoch)))))

      (service.deleteClients _)
        .expects(Map.empty[UserId, Seq[ClientId]])
        .returning(Future.successful({}))

      (convsService.addUnexpectedMembersToConv _)
        .expects(conv.id, Set.empty[UserId])
        .returning(Future.successful({}))

      // When
      result(syncHandler.postOtrMessage(conv.id, msg, TargetRecipients.SpecificClients(targetRecipients), isHidden = false))
    }
  }

  feature("Post client discovery message") {

    scenario("Successfully fetch clients") {
      // Given
      val syncHandler = getSyncHandler
      val conv = ConversationData(ConvId("conv-id"), RConvId("r-conv-id"))
      val encryptedContent = EncryptedContent.Empty

      val missingClients: Map[UserId, Seq[ClientId]] = Map(
        UserId("user1") -> Seq(ClientId("client1"), ClientId("client2")),
        UserId("user2") -> Seq(ClientId("client1"), ClientId("client2"))
      )

      // Expectations
      (convStorage.getByRemoteId _)
        .expects(conv.remoteId)
        .returning(Future.successful(Some(conv)))

      (msgClient.postMessage _)
        .expects(conv.remoteId, OtrMessage(selfClientId, encryptedContent, nativePush = false), *)
        .returning(CancellableFuture.successful(Right(MessageResponse.Success(
          ClientMismatch(
            missing = missingClients,
            time = RemoteInstant.Epoch
          )
        ))))

      // When
      val actualResult = result(syncHandler.postClientDiscoveryMessage(conv.remoteId))

      // Then
      actualResult shouldEqual Right(missingClients)
    }
  }
}
