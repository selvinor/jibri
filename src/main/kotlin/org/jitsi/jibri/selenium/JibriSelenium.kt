/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.jitsi.jibri.selenium

import org.jitsi.jibri.CallParams
import org.jitsi.jibri.CallUrlInfo
import org.jitsi.jibri.selenium.pageobjects.CallPage
import org.jitsi.jibri.selenium.pageobjects.HomePage
import org.jitsi.jibri.service.JibriServiceStatus
import org.jitsi.jibri.util.StatusPublisher
import org.jitsi.jibri.util.extensions.error
import org.jitsi.jibri.util.extensions.scheduleAtFixedRate
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Options that can be passed to [JibriSelenium]
 */
data class JibriSeleniumOptions(
    /**
     * The parameters necessary for joining a call
     */
    val callParams: CallParams,
    /**
     * Which display selenium should be started on
     */
    val display: String = ":0",
    /**
     * The display name that should be used for jibri.  Note that this
     * is currently only used in the sipgateway gateway scenario; when doing
     * recording the jibri is 'invisible' in the call
     */
    val displayName: String = "",
    /**
     * The email that should be used for jibri.  Note that this
     * is currently only used in the sipgateway gateway scenario; when doing
     * recording the jibri is 'invisible' in the call
     */
    val email: String = "",
    /**
     * Chrome command line flags to add (in addition to the common
     * ones)
     */
    val extraChromeCommandLineFlags: List<String> = listOf(),
    /**
     * The url params to be passed in the call url
     */
    val urlParams: List<String>
)

val SIP_GW_URL_OPTIONS = listOf(
    "config.iAmRecorder=true",
    "config.iAmSipGateway=true",
    "config.ignoreStartMuted=true"
)

val RECORDING_URL_OPTIONS = listOf(
    "config.iAmRecorder=true",
    "config.externalConnectUrl=null",
    "interfaceConfig.APP_NAME=\"Jibri\""
)

/**
 * The [JibriSelenium] class is responsible for all of the interactions with
 * Selenium.  It:
 * 1) Owns the webdriver
 * 2) Handles passing the proper options to Chrome
 * 3) Sets the necessary localstorage variables before joining a call
 * It implements [StatusPublisher] to publish its status
 */
class JibriSelenium(
    private val jibriSeleniumOptions: JibriSeleniumOptions,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) : StatusPublisher<JibriServiceStatus>() {
    private val logger = Logger.getLogger(this::class.qualifiedName)
    private var chromeDriver: ChromeDriver
    private val baseUrl: String = jibriSeleniumOptions.callParams.callUrlInfo.baseUrl

    /**
     * A task which checks if Jibri is alone in the call
     */
    private var emptyCallTask: ScheduledFuture<*>? = null

    /**
     * Set up default chrome driver options (using fake device, etc.)
      */
    init {
        System.setProperty("webdriver.chrome.logfile", "/tmp/chromedriver.log")
        val chromeOptions = ChromeOptions()
        chromeOptions.addArguments(
                "--use-fake-ui-for-media-stream",
                "--start-maximized",
                "--kiosk",
                "--enabled",
                "--enable-logging",
                "--vmodule=*=3",
                "--disable-infobars",
                "--alsa-output-device=plug:amix"
        )
        val chromeDriverService = ChromeDriverService.Builder().withEnvironment(
            mapOf("DISPLAY" to jibriSeleniumOptions.display)
        ).build()
        chromeDriver = ChromeDriver(chromeDriverService, chromeOptions)
    }

    /**
     * Set various values to be put in local storage.  NOTE: the driver
     * should have already navigated to the desired page
     */
    private fun setLocalStorageValues(keyValues: Map<String, String>) {
        for ((key, value) in keyValues) {
            chromeDriver.executeScript("window.localStorage.setItem('$key', '$value')")
        }
    }

    /**
     * Check if Jibri is the only participant in the call.  If it is the only
     * participant for 30 seconds, it will leave the call.
     */
    private fun addEmptyCallDetector() {
        var numTimesEmpty = 0
        emptyCallTask = executor.scheduleAtFixedRate(15, TimeUnit.SECONDS) {
            try {
                // >1 since the count will include jibri itself
                if (CallPage(chromeDriver).getNumParticipants(chromeDriver) > 1) {
                    numTimesEmpty = 0
                } else {
                    numTimesEmpty++
                }
                if (numTimesEmpty >= 2) {
                    logger.info("Jibri has been in a lonely call for 30 seconds, marking as finished")
                    emptyCallTask?.cancel(false)
                    publishStatus(JibriServiceStatus.FINISHED)
                }
            } catch (t: Throwable) {
                logger.error("Error while checking for empty call state: $t")
            }
        }
    }

    /**
     * Keep track of all the participants who take part in the call while
     * Jibri is active
     */
    private fun addParticipantTracker() {
        CallPage(chromeDriver).injectParticipantTrackerScript(chromeDriver)
    }

    /**
     * Join a a web call with Selenium
     */
    fun joinCall(callName: String): Boolean {
        HomePage(chromeDriver).visit(CallUrlInfo(baseUrl, ""))
        val xmppUsername = jibriSeleniumOptions.callParams.callLoginParams.username
        val xmppDomain = jibriSeleniumOptions.callParams.callLoginParams.domain
        setLocalStorageValues(mapOf(
            "displayname" to jibriSeleniumOptions.displayName,
            "email" to jibriSeleniumOptions.email,
            "xmpp_username_override" to "$xmppUsername@$xmppDomain",
            "xmpp_password_override" to jibriSeleniumOptions.callParams.callLoginParams.password,
            "callStatsUserName" to "jibri")
        )
        val urlParams = jibriSeleniumOptions.urlParams
        val callNameWithUrlParams = "$callName#${urlParams.joinToString("&")}"
        if (!CallPage(chromeDriver).visit(CallUrlInfo(baseUrl, callNameWithUrlParams))) {
            return false
        }
        addEmptyCallDetector()
        addParticipantTracker()
        return true
    }

    fun getParticipants(): List<Map<String, Any>> {
        return CallPage(chromeDriver).getParticipants(chromeDriver)
    }

    fun leaveCallAndQuitBrowser() {
        emptyCallTask?.cancel(true)
        CallPage(chromeDriver).leave()
        chromeDriver.quit()
    }

    //TODO: helper func to verify connectivity
}
