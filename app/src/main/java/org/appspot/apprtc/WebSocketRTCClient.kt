/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.appspot.apprtc

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

import org.appspot.apprtc.RoomParametersFetcher.RoomParametersFetcherEvents
import org.appspot.apprtc.WebSocketChannelClient.WebSocketChannelEvents
import org.appspot.apprtc.WebSocketChannelClient.WebSocketConnectionState
import org.appspot.apprtc.util.AsyncHttpURLConnection
import org.appspot.apprtc.util.AsyncHttpURLConnection.AsyncHttpEvents
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Negotiates signaling for chatting with https://appr.tc "rooms".
 * Uses the client<->server specifics of the apprtc AppEngine webapp.
 *
 *
 * To use: create an instance of this object (registering a message handler) and
 * call connectToRoom().  Once room connection is established
 * onConnectedToRoom() callback with room parameters is invoked.
 * Messages to other party (with local Ice candidates and answer SDP) can
 * be sent after WebSocket connection is established.
 */
class WebSocketRTCClient(private val events: AppRTCClient.SignalingEvents) : AppRTCClient, WebSocketChannelEvents {

    private val handler: Handler
    private var initiator: Boolean = false
    private var wsClient: WebSocketChannelClient? = null
    private var roomState: ConnectionState
    private var connectionParameters: AppRTCClient.RoomConnectionParameters? = null
    private var messageUrl: String? = null
    private var leaveUrl: String? = null

    private enum class ConnectionState {
        NEW, CONNECTED, CLOSED, ERROR
    }

    private enum class MessageType {
        MESSAGE, LEAVE
    }

    init {
        roomState = ConnectionState.NEW
        val handlerThread = HandlerThread(TAG)
        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    // --------------------------------------------------------------------
    // AppRTCClient interface implementation.
    // Asynchronously connect to an AppRTC room URL using supplied connection
    // parameters, retrieves room parameters and connect to WebSocket server.
    override fun connectToRoom(connectionParameters: AppRTCClient.RoomConnectionParameters) {
        this.connectionParameters = connectionParameters
        handler.post { this.connectToRoomInternal() }
    }

    override fun disconnectFromRoom() {
        handler.post {
            disconnectFromRoomInternal()
            handler.looper.quit()
        }
    }

    // Connects to room - function runs on a local looper thread.
    private fun connectToRoomInternal() {
        val parameters = connectionParameters ?: return
        val connectionUrl = getConnectionUrl(parameters)
        Log.d(TAG, "Connect to room: $connectionUrl")
        roomState = ConnectionState.NEW
        wsClient = WebSocketChannelClient(handler, this)

        val callbacks = object : RoomParametersFetcherEvents {
            override fun onSignalingParametersReady(params: AppRTCClient.SignalingParameters) {
                this@WebSocketRTCClient.handler.post { this@WebSocketRTCClient.signalingParametersReady(params) }
            }

            override fun onSignalingParametersError(description: String) {
                this@WebSocketRTCClient.reportError(description)
            }
        }

        RoomParametersFetcher(connectionUrl, null, callbacks).makeRequest()
    }

    // Disconnect from room and send bye messages - runs on a local looper thread.
    private fun disconnectFromRoomInternal() {
        Log.d(TAG, "Disconnect. Room state: $roomState")
        if (roomState == ConnectionState.CONNECTED) {
            Log.d(TAG, "Closing room.")
            sendPostMessage(MessageType.LEAVE, leaveUrl, null)
        }
        roomState = ConnectionState.CLOSED
        wsClient?.disconnect(true)
    }

    // Helper functions to get connection, post message and leave message URLs
    private fun getConnectionUrl(connectionParameters: AppRTCClient.RoomConnectionParameters): String {
        return (connectionParameters.roomUrl + "/" + ROOM_JOIN + "/" + connectionParameters.roomId
                + getQueryString(connectionParameters))
    }

    private fun getMessageUrl(
            connectionParameters: AppRTCClient.RoomConnectionParameters, signalingParameters: AppRTCClient.SignalingParameters): String {
        return (connectionParameters.roomUrl + "/" + ROOM_MESSAGE + "/" + connectionParameters.roomId
                + "/" + signalingParameters.clientId + getQueryString(connectionParameters))
    }

    private fun getLeaveUrl(
            connectionParameters: AppRTCClient.RoomConnectionParameters, signalingParameters: AppRTCClient.SignalingParameters): String {
        return (connectionParameters.roomUrl + "/" + ROOM_LEAVE + "/" + connectionParameters.roomId + "/"
                + signalingParameters.clientId + getQueryString(connectionParameters))
    }

    private fun getQueryString(connectionParameters: AppRTCClient.RoomConnectionParameters): String {
        return if (connectionParameters.urlParameters != null) {
            "?" + connectionParameters.urlParameters
        } else {
            ""
        }
    }

    // Callback issued when room parameters are extracted. Runs on local
    // looper thread.
    private fun signalingParametersReady(signalingParameters: AppRTCClient.SignalingParameters) {
        val parameters = connectionParameters ?: return

        Log.d(TAG, "Room connection completed.")
        if (!signalingParameters.initiator && signalingParameters.offerSdp == null) {
            Log.w(TAG, "No offer SDP in room response.")
        }
        initiator = signalingParameters.initiator
        messageUrl = getMessageUrl(parameters, signalingParameters)
        leaveUrl = getLeaveUrl(parameters, signalingParameters)
        Log.d(TAG, "Message URL: $messageUrl")
        Log.d(TAG, "Leave URL: $leaveUrl")
        roomState = ConnectionState.CONNECTED

        // Fire connection and signaling parameters events.
        events.onConnectedToRoom(signalingParameters)

        // Connect and register WebSocket client.
        wsClient?.connect(signalingParameters.wssUrl, signalingParameters.wssPostUrl)
        wsClient?.register(parameters.roomId, signalingParameters.clientId)
    }

    // Send local offer SDP to the other participant.
    override fun sendOfferSdp(sdp: SessionDescription) {
        handler.post {
            if (roomState != ConnectionState.CONNECTED) {
                reportError("Sending offer SDP in non connected state.")
                return@post
            }
            val json = JSONObject()
            jsonPut(json, "sdp", sdp.description)
            jsonPut(json, "type", "offer")
            sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString())
        }
    }

    // Send local answer SDP to the other participant.
    override fun sendAnswerSdp(sdp: SessionDescription) {
        handler.post {
            val json = JSONObject()
            jsonPut(json, "sdp", sdp.description)
            jsonPut(json, "type", "answer")
            wsClient?.send(json.toString())
        }
    }

    // Send Ice candidate to the other participant.
    override fun sendLocalIceCandidate(candidate: IceCandidate) {
        handler.post {
            val json = JSONObject()
            jsonPut(json, "type", "candidate")
            jsonPut(json, "label", candidate.sdpMLineIndex)
            jsonPut(json, "id", candidate.sdpMid)
            jsonPut(json, "candidate", candidate.sdp)
            if (initiator) {
                // Call initiator sends ice candidates to GAE server.
                if (roomState != ConnectionState.CONNECTED) {
                    reportError("Sending ICE candidate in non connected state.")
                    return@post
                }
                sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString())
            } else {
                // Call receiver sends ice candidates to websocket server.
                wsClient?.send(json.toString())
            }
        }
    }

    // Send removed Ice candidates to the other participant.
    override fun sendLocalIceCandidateRemovals(candidates: Array<IceCandidate>) {
        handler.post {
            val json = JSONObject()
            jsonPut(json, "type", "remove-candidates")
            val jsonArray = JSONArray()
            for (candidate in candidates) {
                jsonArray.put(toJsonCandidate(candidate))
            }
            jsonPut(json, "candidates", jsonArray)
            if (initiator) {
                // Call initiator sends ice candidates to GAE server.
                if (roomState != ConnectionState.CONNECTED) {
                    reportError("Sending ICE candidate removals in non connected state.")
                    return@post
                }
                sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString())
            } else {
                // Call receiver sends ice candidates to websocket server.
                wsClient?.send(json.toString())
            }
        }
    }

    // --------------------------------------------------------------------
    // WebSocketChannelEvents interface implementation.
    // All events are called by WebSocketChannelClient on a local looper thread
    // (passed to WebSocket client constructor).
    override fun onWebSocketMessage(message: String) {
        if (wsClient?.state != WebSocketConnectionState.REGISTERED) {
            Log.e(TAG, "Got WebSocket message in non registered state.")
            return
        }
        try {
            var json = JSONObject(message)
            val msgText = json.getString("msg")
            val errorText = json.optString("error")
            if (msgText.isNotEmpty()) {
                json = JSONObject(msgText)
                when (val type = json.optString("type")) {
                    "candidate" -> events.onRemoteIceCandidate(toJavaCandidate(json))
                    "remove-candidates" -> {
                        val candidateArray = json.getJSONArray("candidates")
                        val candidates = arrayOfNulls<IceCandidate>(candidateArray.length())
                        for (i in 0 until candidateArray.length()) {
                            candidates[i] = toJavaCandidate(candidateArray.getJSONObject(i))
                        }
                        events.onRemoteIceCandidatesRemoved(candidates)
                    }
                    "answer" -> if (initiator) {
                        val sdp = SessionDescription(
                                SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp"))
                        events.onRemoteDescription(sdp)
                    } else {
                        reportError("Received answer for call initiator: $message")
                    }
                    "offer" -> if (!initiator) {
                        val sdp = SessionDescription(
                                SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp"))
                        events.onRemoteDescription(sdp)
                    } else {
                        reportError("Received offer for call receiver: $message")
                    }
                    "bye" -> events.onChannelClose()
                    else -> reportError("Unexpected WebSocket message: $message")
                }
            } else {
                if (errorText != null && errorText.isNotEmpty()) {
                    reportError("WebSocket error message: $errorText")
                } else {
                    reportError("Unexpected WebSocket message: $message")
                }
            }
        } catch (e: JSONException) {
            reportError("WebSocket message JSON parsing error: $e")
        }

    }

    override fun onWebSocketClose() {
        events.onChannelClose()
    }

    override fun onWebSocketError(description: String) {
        reportError("WebSocket error: $description")
    }

    // --------------------------------------------------------------------
    // Helper functions.
    private fun reportError(errorMessage: String) {
        Log.e(TAG, errorMessage)
        handler.post {
            if (roomState != ConnectionState.ERROR) {
                roomState = ConnectionState.ERROR
                events.onChannelError(errorMessage)
            }
        }
    }

    // Send SDP or ICE candidate to a room server.
    private fun sendPostMessage(
            messageType: MessageType, url: String?, message: String?) {
        var logInfo = url
        if (message != null) {
            logInfo += ". Message: $message"
        }
        Log.d(TAG, "C->GAE: $logInfo")
        val httpConnection = AsyncHttpURLConnection("POST", url, message, object : AsyncHttpEvents {
            override fun onHttpError(errorMessage: String) {
                reportError("GAE POST error: $errorMessage")
            }

            override fun onHttpComplete(response: String) {
                if (messageType == MessageType.MESSAGE) {
                    try {
                        val roomJson = JSONObject(response)
                        val result = roomJson.getString("result")
                        if (result != "SUCCESS") {
                            reportError("GAE POST error: $result")
                        }
                    } catch (e: JSONException) {
                        reportError("GAE POST JSON error: $e")
                    }

                }
            }
        })
        httpConnection.send()
    }

    // Converts a Java candidate to a JSONObject.
    private fun toJsonCandidate(candidate: IceCandidate): JSONObject {
        val json = JSONObject()
        jsonPut(json, "label", candidate.sdpMLineIndex)
        jsonPut(json, "id", candidate.sdpMid)
        jsonPut(json, "candidate", candidate.sdp)
        return json
    }

    // Converts a JSON candidate to a Java object.
    @Throws(JSONException::class)
    internal fun toJavaCandidate(json: JSONObject): IceCandidate {
        return IceCandidate(
                json.getString("id"), json.getInt("label"), json.getString("candidate"))
    }

    companion object {

        private const val TAG = "WSRTCClient"
        private const val ROOM_JOIN = "join"
        private const val ROOM_MESSAGE = "message"
        private const val ROOM_LEAVE = "leave"

        // Put a |key|->|value| mapping in |json|.
        private fun jsonPut(json: JSONObject, key: String, value: Any) {
            try {
                json.put(key, value)
            } catch (e: JSONException) {
                throw RuntimeException(e)
            }

        }
    }
}
