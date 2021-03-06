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

package org.jitsi.jibri.api.xmpp

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriIq
import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriIqProvider
import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriStatusPacketExt
import org.jitsi.jibri.FileRecordingRequestParams
import org.jitsi.jibri.JibriManager
import org.jitsi.jibri.StartServiceResult
import org.jitsi.jibri.config.XmppEnvironmentConfig
import org.jitsi.jibri.health.EnvironmentContext
import org.jitsi.jibri.selenium.CallParams
import org.jitsi.jibri.service.JibriServiceStatus
import org.jitsi.jibri.service.JibriServiceStatusHandler
import org.jitsi.jibri.service.ServiceParams
import org.jitsi.jibri.service.impl.SipGatewayServiceParams
import org.jitsi.jibri.service.impl.StreamingParams
import org.jitsi.jibri.sipgateway.SipClientParams
import org.jitsi.jibri.util.NameableThreadFactory
import org.jitsi.jibri.util.extensions.error
import org.jitsi.jibri.util.getCallUrlInfoFromJid
import org.jitsi.xmpp.TrustAllHostnameVerifier
import org.jitsi.xmpp.TrustAllX509TrustManager
import org.jitsi.xmpp.mucclient.MucClient
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * [XmppApi] connects to XMPP MUCs according to the given [XmppEnvironmentConfig]s (which are
 * parsed from config.json) and listens for IQ messages which contain Jibri commands, which it relays
 * to the given [JibriManager].  The IQ messages are instances of [JibriIq] and allow the
 * starting and stopping of the services Jibri provides.
 * [XmppApi] subscribes to [JibriManager] status updates and translates those into
 * XMPP presence (defined by [JibriStatusPacketExt]) updates to advertise the status of this Jibri.
 * [XmppApi] takes care of translating the XMPP commands into the appropriate
 * [JibriManager] API calls and translates the results into XMPP IQ responses.
 */
class XmppApi(
    private val jibriManager: JibriManager,
    private val xmppConfigs: List<XmppEnvironmentConfig>
) {
    private val logger = Logger.getLogger(this::class.qualifiedName)
    private val executor = Executors.newSingleThreadExecutor(NameableThreadFactory("XmppApi"))
    private val defaultMucClientProvider = { config: XMPPTCPConnectionConfiguration ->
        MucClient(config)
    }

    /**
     * Start up the XMPP API by connecting and logging in to all the configured XMPP environments.  For each XMPP
     * connection, we'll listen for incoming [JibriIq] messages and handle them appropriately.  Join the MUC on
     * each connection and send an initial [JibriStatusPacketExt] presence.
     */
    fun start(mucClientProvider: (XMPPTCPConnectionConfiguration) -> MucClient = defaultMucClientProvider) {
        JibriStatusPacketExt.registerExtensionProvider()
        ProviderManager.addIQProvider(
            JibriIq.ELEMENT_NAME, JibriIq.NAMESPACE, JibriIqProvider()
        )

        // Join all the MUCs we've been told to
        for (config in xmppConfigs) {
            for (host in config.xmppServerHosts) {
                logger.info("Connecting to xmpp environment on $host with config $config")
                val configBuilder = XMPPTCPConnectionConfiguration.builder()
                    .setHost(host)
                    .setXmppDomain(config.controlLogin.domain)
                    .setUsernameAndPassword(config.controlLogin.username, config.controlLogin.password)
                if (config.trustAllXmppCerts) {
                    logger.info("The trustAllXmppCerts config is enabled for this domain, " +
                            "all XMPP server provided certificates will be accepted")
                    configBuilder.setCustomX509TrustManager(TrustAllX509TrustManager())
                    configBuilder.setHostnameVerifier(TrustAllHostnameVerifier())
                }
                try {
                    val mucClient = mucClientProvider(configBuilder.build())
                    mucClient.addIqRequestHandler(object : JibriSyncIqRequestHandler() {
                        override fun handleJibriIqRequest(jibriIq: JibriIq): IQ {
                            return handleJibriIq(jibriIq, config, mucClient)
                        }
                    })
                    val jibriJid = JidCreate.bareFrom("${config.controlMuc.roomName}@${config.controlMuc.domain}")
                    jibriManager.addStatusHandler { status ->
                        logger.info("Jibri reports its status is now $status, publishing presence to connection ${config.name}")
                        val jibriPresence = JibriPresenceHelper.createPresence(status, jibriJid)
                        mucClient.sendStanza(jibriPresence)
                    }
                    // The recording control muc
                    mucClient.createOrJoinMuc(
                        jibriJid.asEntityBareJidIfPossible(),
                        Resourcepart.from(config.controlMuc.nickname)
                    )
                    val jibriPresence = JibriPresenceHelper.createPresence(JibriStatusPacketExt.Status.IDLE, jibriJid)
                    mucClient.sendStanza(jibriPresence)

                    // The SIP control muc
                    config.sipControlMuc?.let {
                        logger.info("SIP control muc is defined for environment ${config.name}, joining")
                        mucClient.createOrJoinMuc(
                            JidCreate.entityBareFrom("${config.sipControlMuc.roomName}@${config.sipControlMuc.domain}"),
                            Resourcepart.from(config.sipControlMuc.nickname)
                        )
                        val jibriSipStatus = JibriPresenceHelper.createPresence(
                            JibriStatusPacketExt.Status.IDLE,
                            JidCreate.bareFrom("${config.sipControlMuc.roomName}@${config.sipControlMuc.domain}")
                        )
                        mucClient.sendStanza(jibriSipStatus)
                    }
                } catch (e: Exception) {
                    logger.error("Error connecting to xmpp environment: $e")
                }
            }
        }
    }

    /**
     * Helper function to handle a [JibriIq] message with the context of the [XmppEnvironmentConfig] and [MucClient]
     * that this [JibriIq] was received on.
     */
    private fun handleJibriIq(jibriIq: JibriIq, xmppEnvironment: XmppEnvironmentConfig, mucClient: MucClient): IQ {
        logger.info("Received JibriIq ${jibriIq.toXML()} from environment ${xmppEnvironment.name}")
        return when (jibriIq.action) {
            JibriIq.Action.START -> handleStartJibriIq(jibriIq, xmppEnvironment, mucClient)
            JibriIq.Action.STOP -> handleStopJibriIq(jibriIq)
            else -> IQ.createErrorResponse(
                jibriIq,
                XMPPError.getBuilder().setCondition(XMPPError.Condition.bad_request))
        }
    }

    /**
     * Handle a start [JibriIq] message.  We'll respond immediately with a [JibriIq.Status.PENDING] IQ response and
     * send a new IQ with the subsequent stats after starting the service:
     * [JibriIq.Status.FAILED] if there was an error starting the service (or an error while the service was running)
     * [JibriIq.Status.BUSY] if the Jibri was already busy and couldn't start the requested service
     * [JibriIq.Status.ON] if the service started successfully
     */
    private fun handleStartJibriIq(
        startJibriIq: JibriIq,
        xmppEnvironment: XmppEnvironmentConfig,
        mucClient: MucClient
    ): IQ {
        logger.info("Received start request")
        // We don't want to block the response to wait for the service to actually start, so submit a job to
        // start the service asynchronously and send an IQ with the status after its done.
        executor.submit {
            val resultIq = JibriIqHelper.create(startJibriIq.from)
            try {
                logger.info("Starting service")

                // If there is an issue with the service while it's running, we need to send an IQ
                // to notify the caller who invoked the service of its status, so we'll listen
                // for the service's status while it's running and this method will be invoked
                // if it changes
                val serviceStatusHandler: JibriServiceStatusHandler = { serviceStatus ->
                    when (serviceStatus) {
                        JibriServiceStatus.ERROR -> {
                            val errorIq = JibriIqHelper.create(startJibriIq.from, status = JibriIq.Status.FAILED)
                            logger.info("Current service had an error, sending error iq ${errorIq.toXML()}")
                            mucClient.sendStanza(errorIq)
                        }
                        JibriServiceStatus.FINISHED -> {
                            val offIq = JibriIqHelper.create(startJibriIq.from, status = JibriIq.Status.OFF)
                            logger.info("Current service finished, sending off iq ${offIq.toXML()}")
                            mucClient.sendStanza(offIq)
                        }
                    }
                }
                val startServiceResult = handleStartService(startJibriIq, xmppEnvironment, serviceStatusHandler)

                resultIq.status = when (startServiceResult) {
                    StartServiceResult.SUCCESS -> JibriIq.Status.ON
                    StartServiceResult.BUSY -> JibriIq.Status.BUSY
                    StartServiceResult.ERROR -> JibriIq.Status.FAILED
                }
            } catch (e: Throwable) {
                logger.error("Error in startService task: $e")
                resultIq.status = JibriIq.Status.FAILED
            } finally {
                logger.info("Sending start service response iq: ${resultIq.toXML()}")
                mucClient.sendStanza(resultIq)
            }
        }
        // Immediately respond that the request is pending
        val initialResponse = JibriIqHelper.createResult(startJibriIq, JibriIq.Status.PENDING)
        logger.info("Sending 'pending' response to start IQ")
        return initialResponse
    }

    /**
     * Handle a stop [JibriIq] message to stop the currently running service (if there is one).  Send a [JibriIq]
     * response with [JibriIq.Status.OFF].
     */
    private fun handleStopJibriIq(stopJibriIq: JibriIq): IQ {
        jibriManager.stopService()
        // By this point the service has been fully stopped
        return JibriIqHelper.createResult(stopJibriIq, JibriIq.Status.OFF)
    }

    /**
     * Helper function to actually start the service.  We need to parse the fields in the [JibriIq] message
     * to determine which [JibriManager] service API to call, as well as convert the types into what [JibriManager]
     * expects
     */
    private fun handleStartService(
        startIq: JibriIq,
        xmppEnvironment: XmppEnvironmentConfig,
        serviceStatusHandler: JibriServiceStatusHandler
    ): StartServiceResult {
        val callUrlInfo = getCallUrlInfoFromJid(
            startIq.room,
            xmppEnvironment.stripFromRoomDomain,
            xmppEnvironment.xmppDomain
        )
        val callParams = CallParams(callUrlInfo)
        logger.info("Parsed call url info: $callUrlInfo")
        return when (startIq.mode()) {
            JibriMode.FILE -> {
                jibriManager.startFileRecording(
                    ServiceParams(xmppEnvironment.usageTimeoutMins),
                    FileRecordingRequestParams(callParams, xmppEnvironment.callLogin),
                    EnvironmentContext(xmppEnvironment.name),
                    serviceStatusHandler
                )
            }
            JibriMode.STREAM -> {
                jibriManager.startStreaming(
                    ServiceParams(xmppEnvironment.usageTimeoutMins),
                    StreamingParams(
                        callParams,
                        xmppEnvironment.callLogin,
                        youTubeStreamKey = startIq.streamId,
                        youTubeBroadcastId = startIq.youtubeBroadcastId),
                    EnvironmentContext(xmppEnvironment.name),
                    serviceStatusHandler
                )
            }
            JibriMode.SIPGW -> {
                jibriManager.startSipGateway(
                    ServiceParams(xmppEnvironment.usageTimeoutMins),
                    SipGatewayServiceParams(callParams, SipClientParams(startIq.sipAddress, startIq.displayName)),
                    EnvironmentContext(xmppEnvironment.name),
                    serviceStatusHandler
                )
            }
            else -> {
                StartServiceResult.ERROR
            }
        }
    }
}
