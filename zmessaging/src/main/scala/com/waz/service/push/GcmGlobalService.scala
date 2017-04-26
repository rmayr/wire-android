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
package com.waz.service.push

import android.content.Context
import com.google.android.gms.common.{ConnectionResult, GooglePlayServicesUtil}
import com.google.firebase.iid.FirebaseInstanceId
import com.localytics.android.Localytics
import com.waz.HockeyApp
import com.waz.HockeyApp.NoReporting
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.content.GlobalPreferences.GcmEnabledKey
import com.waz.content.{GlobalPreferences, Preferences}
import com.waz.model._
import com.waz.service.push.GcmGlobalService.{GcmRegistration, PushSenderId}
import com.waz.service.{BackendConfig, MetaDataService}
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.EventContext
import com.waz.utils.{LoggedTry, returning}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.{NoStackTrace, NonFatal}

class GcmGlobalService(context: Context, implicit val prefs: GlobalPreferences, metadata: MetaDataService, backendConfig: BackendConfig) {

  implicit val dispatcher = new SerialDispatchQueue(name = "GcmGlobalDispatchQueue")

  private implicit val ev = EventContext.Global

  import metadata._

  val gcmSenderId: PushSenderId = backendConfig.gcmSenderId

  lazy val gcmCheckResult = try GooglePlayServicesUtil.isGooglePlayServicesAvailable(context) catch {
    case ex: Throwable =>
      error(s"GooglePlayServices availability check failed", ex)
      ConnectionResult.DEVELOPER_ERROR
  }

  val gcmEnabled = prefs.preference(GcmEnabledKey).signal

  def gcmAvailable = prefs.getFromPref(GcmEnabledKey) && gcmCheckResult == ConnectionResult.SUCCESS

  def getGcmRegistration: Future[GcmRegistration] = GcmRegistration() map { reg =>
    if (reg.version == appVersion) reg
    else GcmRegistration("", AccountId(""), appVersion)
  }

  def clearGcm(user: AccountId) = GcmRegistration() map { reg =>
    verbose(s"clearGcmRegistrationUser($user): $reg")
    if (reg.user == user) {
      GcmRegistration.update(reg.copy(user = AccountId("")))
    }
  }

  //removes the current gcm token and generates a new one - ensures that the user shouldn't be left without a GCM token
  def resetGcm(user: AccountId): Future[Option[GcmRegistration]] = unregister().flatMap { _ =>
    withGcm {
      LoggedTry {deleteInstanceId()} // if localytics registered first with only their sender id, we have to unregister so that our own additional sender id gets registered, too
      try {
        val token = getFcmToken
        Localytics.setPushDisabled(false)
        Localytics.setPushRegistrationId(token)
        Future.successful(Some(setGcm(token, AccountId(""))))
      } catch {
        case NonFatal(ex) =>
          setGcm("", AccountId(""))
          warn(s"registerGcm failed for sender: '$gcmSenderId'", ex)
          HockeyApp.saveException(ex, s"unable to register gcm for sender $gcmSenderId")
          Future.successful(None)
      }
    }
  }

  private def setGcm(token: String, user: AccountId): GcmRegistration = {
    val reg = GcmRegistration(token, user, appVersion)
    verbose(s"setGcmRegistration: $reg")
    GcmRegistration.update(reg)
    reg
  }

  //used to indicate that the token was registered properly with the BE - no user indicates it's not registered
  def updateRegisteredUser(token: String, user: AccountId) = GcmRegistration() map { reg =>
    if (reg.token == token && reg.user != user) {
      val updated = reg.copy(user = user, version = appVersion)
      GcmRegistration.update(updated)
      updated
    } else reg
  }

  def unregister() = GcmRegistration.update(GcmRegistration.empty) map { _ => deleteInstanceId() } recover { case NonFatal(e) => warn("unable to unregister from GCM", e) }

  private def withGcm[A](body: => A): A = if (gcmAvailable) body else throw new GcmGlobalService.GcmNotAvailableException

  //TODO do we need the scope here? GoogleCloudMessaging.INSTANCE_ID_SCOPE
  private def getFcmToken = returning(FirebaseInstanceId.getInstance().getToken()) { t =>
    if (t == null) throw new Exception("No FCM token was returned from the FirebaseInstanceId")
  }

  //Deleting the instance id also removes any tokens the instance id was using
  private def deleteInstanceId(): Unit = LoggedTry.local { FirebaseInstanceId.getInstance().deleteInstanceId() }
}

object GcmGlobalService {

  case class PushSenderId(str: String) extends AnyVal

  class GcmNotAvailableException extends Exception("Google Play Services not available") with NoReporting with NoStackTrace

  case class GcmRegistration(token: String, user: AccountId, version: Int)

  object GcmRegistration {

    lazy val empty = GcmRegistration("", AccountId(""), 0)

    import GlobalPreferences._
    def apply()(implicit ec: ExecutionContext, prefs: Preferences): Future[GcmRegistration] = for {
      token   <- prefs.preference(GcmRegistrationIdPref).apply()
      userId  <- prefs.preference(GcmRegistrationUserPref).apply()
      version <- prefs.preference(GcmRegistrationVersionPref).apply()
    } yield GcmRegistration(token, AccountId(userId), version)

    def update(reg: GcmRegistration)(implicit ec: ExecutionContext, prefs: Preferences) = for {
      _ <- prefs.preference(GcmRegistrationUserPref) := reg.token
      _ <- prefs.preference(GcmRegistrationUserPref) := reg.user.str
      _ <- prefs.preference(GcmRegistrationVersionPref) := reg.version
    } yield {}
  }
}
