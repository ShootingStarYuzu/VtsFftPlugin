package me.yuzu.vts.fft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.yuzu.vts.fft.FftService.FftData;

public class VtsService {

	private final List<StatusListener> statusListener;
	private final Gson gson;

	private final Object connectionLock;

	private volatile Status connectionStatus;
	private volatile Thread connectionThread;
	private volatile IOException connectionError;

	private final Queue<FftData> handlerQueue;
	private volatile HandlerStatus handlerStatus;
	private volatile Settings handlerSettings;

	public VtsService() {
		this.statusListener = new ArrayList<>();
		this.gson = new GsonBuilder().serializeNulls().create();

		this.connectionLock = new Object();
		this.connectionStatus = Status.Disconnected;
		this.connectionThread = null;
		this.connectionError = null;

		this.handlerQueue = new LinkedList<>();
		this.handlerStatus = HandlerStatus.Initializing;
		this.handlerSettings = null;
	}

	public Status getConnectionStatus() {
		return connectionStatus;
	}

	public final void connect(Settings settings) throws IOException {
		synchronized (connectionLock) {
			// Finalize existing connection attempts.
			if (connectionLock == Status.Disconnecting) {
				waitWhileConnectionStatusIs(Status.Disconnecting);
			} else if (connectionStatus == Status.Connecting || connectionStatus == Status.Connected) {
				setConnectionStatus(Status.Disconnecting);
				connectionThread.interrupt();
				waitWhileConnectionStatusIs(Status.Disconnecting);
			}

			// Clear old data first.
			handlerQueue.clear();

			// Now connect to VTube Studio in another thread.
			setConnectionStatus(Status.Connecting);
			connectionThread = new Thread(() -> run(settings));
			connectionThread.start();
			waitWhileConnectionStatusIs(Status.Connecting);

			// If an error happened during the connection
			// re-throw it for the user to be notified.
			if (connectionError != null) {
				throw connectionError;
			}
		}
	}

	public final void disconnect() throws IOException {
		synchronized (connectionLock) {
			// Wait for older disconnect / connect attempts to finish.
			if (connectionStatus == Status.Disconnecting) {
				waitWhileConnectionStatusIs(Status.Disconnecting);
			} else if (connectionStatus == Status.Connecting || connectionStatus == Status.Connected) {
				// Start a disconnect attempt.
				setConnectionStatus(Status.Disconnecting);
				connectionThread.interrupt();
				connectionLock.notifyAll();
				waitWhileConnectionStatusIs(Status.Disconnecting);
			}

			// If an error happened during the disconnect
			// re-throw it for the user to be notified.
			if (connectionError != null) {
				throw connectionError;
			}
		}
	}

	public final void queueFftData(FftData fftData) {
		// Clear old queue values if we are disconnected
		synchronized (connectionLock) {
			if (connectionStatus != Status.Connected) {
				handlerQueue.clear();
				return;
			}

			// Notify the handler about the new data.
			handlerQueue.add(fftData);
			if (handlerStatus == HandlerStatus.WaitForFftData) {
				handlerStatus = HandlerStatus.InjectFftData;
			}
			connectionLock.notifyAll();
		}
	}

	private final void waitWhileConnectionStatusIs(Status status) {
		while (connectionStatus == status) {
			try {
				// Wait on the lock such that the other worker thread
				// can notify this thread of changes.
				connectionLock.wait();
			} catch (InterruptedException exception) { }
		}
	}

	private final void run(Settings settings) {
		WebSocketSession webSocketSession = null;
		IOException exception = null;

		try {
			// Check if the connection attempt was already cancelled.
			synchronized (connectionLock) {
				if (connectionStatus == Status.Disconnecting) {
					connectionThread = null;
					connectionError = new IOException("The connection has been cancelled!");
					setConnectionStatus(Status.Disconnected);
					connectionLock.notifyAll();
				}
			}

			final Handler handler = new Handler();
			final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
			webSocketSession = webSocketClient.doHandshake(handler, settings.connectionUrl).get();

			synchronized (connectionLock) {
				// Check if the connection attempt was already cancelled.
				// Only if we still want to start then we change the status to started.
				// Otherwise we will drop through the loop below and set the status
				// immediately to idle.
				if (connectionStatus == Status.Disconnecting) {
					connectionError = null;
					setConnectionStatus(Status.Connected);
					connectionLock.notifyAll();
				}
			}

			synchronized (connectionLock) {
				handlerSettings = settings;
				handlerStatus = HandlerStatus.Initializing;
			}

			final int channels = settings.stereo ? 2 : 1;
			final int buckets = settings.fftBuckets;
			final float startFrequency = settings.frequencyStart;
			final float endFrequency = settings.frequencyEnd;

			// This is an interlocking state machine for sending messages to VTS.
			// The handler receives responses from VTS and informs this thread with the response.
			// TODO: Change into asynchronous API at some point in time.
			boolean terminate = false;
			int createParameterBucket = 0;
			int createParameterChannel = 0;
			int createParameterType = 0;
			while (!Thread.interrupted() && !terminate && connectionStatus != Status.Disconnecting) {
				synchronized (connectionLock) {
					switch (handlerStatus) {
					case Initializing:
						// Depending if we have an old authentication token try to connect with that first.
						handlerStatus = (settings.authenticationToken == null || settings.authenticationToken.isEmpty())
							? HandlerStatus.AuthenticationTokenRequest
							: HandlerStatus.AuthenticationRequest;
						break;

					case AuthenticationTokenRequest:
					{
						// We have no token saved so acquire a new one.
						final JsonObject jsonData = new JsonObject();
						jsonData.addProperty("pluginName", Plugin.getPluginName());
						jsonData.addProperty("pluginDeveloper", Plugin.getPluginDeveloper());
						jsonData.addProperty("pluginIcon", Plugin.getPluginIconBase64());
						webSocketSession.sendMessage(createVtsJsonMessage("AuthenticationTokenRequest", jsonData));
						waitWhileHandlerStatusIs(HandlerStatus.AuthenticationTokenRequest);
						break;
					}

					case AuthenticationRequest:
					{
						// There has been a token saved so try this one first.
						final JsonObject jsonData = new JsonObject();
						jsonData.addProperty("pluginName", Plugin.getPluginName());
						jsonData.addProperty("pluginDeveloper", Plugin.getPluginDeveloper());
						jsonData.addProperty("pluginIcon", Plugin.getPluginIconBase64());
						jsonData.addProperty("authenticationToken", settings.authenticationToken);
						webSocketSession.sendMessage(createVtsJsonMessage("AuthenticationRequest", jsonData));
						waitWhileHandlerStatusIs(HandlerStatus.AuthenticationRequest);
						break;
					}

					case AuthenticationDenied:
						// The user denied the request, so stop the connection.
						handlerStatus = HandlerStatus.Terminated;
						exception = new IOException("User has denied the API connection!");
						terminate = true;
						break;

					case Authenticated:
						// We are authenticated so create the required parameters.
						handlerStatus = HandlerStatus.CreateTrackingParameters;
						break;

					case CreateTrackingParameters:
					{
						final String type = (createParameterType == 0) ? "Level" : "Peak";
						final String channel = createChannelName(createParameterChannel, channels);

						final JsonObject jsonData = new JsonObject();
						jsonData.addProperty("parameterName", String.format("FrequencyRange%d%s%s", createParameterBucket + 1, channel, type));
						jsonData.addProperty("explanation", createTrackingParameterDescription(createParameterBucket + 1, buckets, startFrequency, endFrequency, type));
						jsonData.addProperty("min", 0);
						jsonData.addProperty("max", 50);
						jsonData.addProperty("defaultValue", 0);
						webSocketSession.sendMessage(createVtsJsonMessage("ParameterCreationRequest", jsonData));
						waitWhileHandlerStatusIs(HandlerStatus.CreateTrackingParameters);
						break;
					}

					case CreateTrackingParameterSuccessful:
						// Toggle between "Level" and "Peak" parameter types.
						createParameterType++;
						if (createParameterType < 2) {
							handlerStatus = HandlerStatus.CreateTrackingParameters;
							break;
						}

						// Create "Level" and "Peak" for each bucket.
						createParameterType = 0;
						createParameterBucket++;
						if (createParameterBucket < buckets) {
							handlerStatus = HandlerStatus.CreateTrackingParameters;
							break;
						}

						// Create "Level" and "Peak" for each bucket for each channel.
						createParameterType = 0;
						createParameterBucket = 0;
						createParameterChannel++;
						if (createParameterChannel < channels) {
							handlerStatus = HandlerStatus.CreateTrackingParameters;
							break;
						}

						handlerStatus = HandlerStatus.InitializationFinished;
						break;

					case CreateTrackingParameterFailed:
					{
						final String type = (createParameterType == 0) ? "Level" : "Peak";
						final String channel = createChannelName(createParameterChannel, channels);

						handlerStatus = HandlerStatus.Terminated;
						exception = new IOException(String.format("Could not create tracking parameter %s", String.format("FrequencyRange%d%s%s", createParameterBucket + 1, channel, type)));
						terminate = true;
						break;
					}

					case InitializationFinished:
						synchronized (connectionLock) {
							// Check if we still intend to connect.
							if (connectionStatus == Status.Connecting) {
								setConnectionStatus(Status.Connected);
								connectionLock.notifyAll();
							} else {
								terminate = true;
								break;
							}
						}
						handlerStatus = HandlerStatus.WaitForFftData;
						break;

					case WaitForFftData:
						// If there is data to send then do it now.
						if (!handlerQueue.isEmpty()) {
							handlerStatus = HandlerStatus.InjectFftData;
							break;
						}

						// While the queue is empty set this thread sleeping
						// and wait for an update.
						waitWhileHandlerStatusIs(HandlerStatus.WaitForFftData);
						break;

					case InjectFftData:
					{
						// We have some data to send, so convert it to JSON and
						// forward it to VTube Studio.
						final FftData fftData = handlerQueue.remove();

						final JsonArray parameterValues = new JsonArray();
						for (int channel = 0; channel < fftData.getChannels(); channel++) {
							for (int bucket = 0; bucket < fftData.getBuckets(); bucket++) {
								final String channelName = createChannelName(channel, fftData.getChannels());

								final JsonObject levelParameter = new JsonObject();
								levelParameter.addProperty("id", String.format("FrequencyRange%d%sLevel", bucket + 1, channelName));
								levelParameter.addProperty("value", fftData.getLevel(channel, bucket) * 50.0f);
								parameterValues.add(levelParameter);

								final JsonObject peakParameter = new JsonObject();
								peakParameter.addProperty("id", String.format("FrequencyRange%d%sPeak", bucket + 1, channelName));
								peakParameter.addProperty("value", fftData.getPeak(channel, bucket) * 50.0f);
								parameterValues.add(peakParameter);
							}
						}

						final JsonObject jsonData = new JsonObject();
						jsonData.add("parameterValues", parameterValues);
						webSocketSession.sendMessage(createVtsJsonMessage("InjectParameterDataRequest", jsonData));
						waitWhileHandlerStatusIs(HandlerStatus.InjectFftData);
						break;
					}

					case InjectFftDataSuccessful:
						// Check if we can directly continue because there is work.
						// If not then set the thread to sleep and wait for work.
						if (handlerQueue.isEmpty()) {
							handlerStatus = HandlerStatus.WaitForFftData;
						} else {
							handlerStatus = HandlerStatus.InjectFftData;
						}
						break;

					case InjectFftDataFailed:
						handlerStatus = HandlerStatus.Terminated;
						exception = new IOException("VTS did not accept the VTS parameter values!");
						terminate = true;
						break;

					case InvalidResponse:
						handlerStatus = HandlerStatus.Terminated;
						exception = new IOException("VTS has sent an unexpected response!");
						terminate = true;
						break;

					case Terminated:
						terminate = true;
						break;
					}
				}
			}

			webSocketSession.close();
		} catch (ExecutionException executionException) {
			exception = new IOException("Could not connect to the VTS API!", executionException.getCause());
		} catch (InterruptedException interruptedException) {
			exception = null;
		} catch (IOException ioException) {
			exception = (ioException.getCause() instanceof InterruptedException) ? null : ioException;
		} catch (IllegalStateException illegalStateException) {
			exception = new IOException("The VTS API connection has been terminated!", illegalStateException);
		} finally {
			if (webSocketSession != null) {
				try {
					webSocketSession.close();
				} catch (IOException ioException) { }
			}

			// Notify the other thread about the connection change.
			synchronized (connectionLock) {
				if (connectionStatus != Status.Disconnected) {
					connectionThread = null;
					connectionError = exception;
					setConnectionStatus(Status.Disconnected);
					connectionLock.notifyAll();
				}
			}
		}
	}

	private final void waitWhileHandlerStatusIs(HandlerStatus status) {
		while (handlerStatus == status){
			if (connectionStatus == Status.Disconnecting || connectionStatus == Status.Disconnected) {
				break;
			}

			try {
				// Wait on the lock such that the other worker thread
				// can notify this thread of changes.
				connectionLock.wait();
			} catch (InterruptedException exception) { }
		}
	}

	private final String createTrackingParameterDescription(int bucket, int buckets, float frequencyStart, float frequencyEnd, String type) {
		final float lowerFrequency = (float) bucket / buckets * (frequencyEnd - frequencyStart) + frequencyStart;
		final float upperFrequency = (float) (bucket + 1) / buckets * (frequencyEnd - frequencyStart) + frequencyStart;
		return String.format("Loudness of the audio in the frequency range from %.2f Hz to %.2f Hz", lowerFrequency, upperFrequency);
	}

	private final String createChannelName(int channel, int channels) {
		if (channels == FftData.CHANNELS_MONO) {
			if (channel == FftData.CHANNEL_MONO) {
				return "";
			}

			throw new IllegalArgumentException("Invalid channel " + channel + "for mono data");
		}

		if (channels == FftData.CHANNELS_STEREO) {
			if (channel == FftData.CHANNEL_LEFT) {
				return "L";
			}
			if (channel == FftData.CHANNEL_RIGHT) {
				return "R";
			}

			throw new IllegalArgumentException("Invalid channel " + channel + "for stereo data");
		}

		throw new IllegalArgumentException("Invalid channel configuration: " + channels);
	}

	public final void addStatusListener(StatusListener listener) {
		this.statusListener.add(listener);
	}

	public final void removeStatusListener(StatusListener listener) {
		this.statusListener.remove(listener);
	}

	public final void clearStatusListener() {
		this.statusListener.clear();
	}

	private final void setConnectionStatus(Status status) {
		this.connectionStatus = status;

		for (StatusListener listener : statusListener) {
			if (listener != null) {
				listener.onStatusUpdate(status);
			}
		}
	}

	private final TextMessage createVtsJsonMessage(String messageType, JsonObject jsonData) {
		JsonObject json = new JsonObject();
		json.addProperty("apiName", "VTubeStudioPublicAPI");
		json.addProperty("apiVersion", "1.0");
		json.addProperty("requestID", UUID.randomUUID().toString());
		json.addProperty("messageType", messageType);
		json.add("data", jsonData);
		return new TextMessage(gson.toJson(json));
	}

	public class Handler extends TextWebSocketHandler {

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
			// There has been a response from VTube Studio so process it.
			final String payload = message.getPayload();
			final JsonElement json = JsonParser.parseString(payload);
			if (!json.isJsonObject()) {
				return;
			}

			final JsonObject jsonObject = json.getAsJsonObject();
			if (!jsonObject.has("apiName") || !jsonObject.has("messageType") || !jsonObject.has("data"))
			if (jsonObject.get("apiName").getAsString().equals("VTubeStudioPublicAPI")) {
				return;
			}

			// The JSON data received is a VTube Studio packet.
			final String messageType = jsonObject.get("messageType").getAsString();
			final JsonObject jsonData = jsonObject.get("data").getAsJsonObject();

			// Notify the other thread about the response.
			synchronized (connectionLock) {
			switch (handlerStatus) {
				case Initializing:
					handlerStatus = HandlerStatus.InvalidResponse;
					break;

				case AuthenticationTokenRequest:
					if (messageType.equals("APIError")) {
						handlerStatus = HandlerStatus.AuthenticationDenied;
					} else if (messageType.equals("AuthenticationTokenResponse") && jsonData.has("authenticationToken")) {
						handlerStatus = HandlerStatus.AuthenticationRequest;
						handlerSettings.authenticationToken = jsonData.get("authenticationToken").getAsString();
					} else {
						handlerStatus = HandlerStatus.InvalidResponse;
					}
					break;

				case AuthenticationRequest:
					if (messageType.equals("APIError")) {
						handlerStatus = HandlerStatus.AuthenticationTokenRequest;
					} else if (messageType.equals("AuthenticationResponse") && jsonData.has("authenticated")) {
						handlerStatus = jsonData.get("authenticated").getAsBoolean()
							? HandlerStatus.Authenticated
							// Authentication has been declined
							: HandlerStatus.AuthenticationTokenRequest;
					} else {
						handlerStatus = HandlerStatus.InvalidResponse;
					}
					break;

				case CreateTrackingParameters:
					if (messageType.equals("APIError")) {
						handlerStatus = HandlerStatus.CreateTrackingParameterFailed;
					} else if (messageType.equals("ParameterCreationResponse") && jsonData.has("parameterName")) {
						handlerStatus = HandlerStatus.CreateTrackingParameterSuccessful;
					} else {
						handlerStatus = HandlerStatus.InvalidResponse;
					}
					break;

				case InjectFftData:
					if (messageType.equals("APIError")) {
						handlerStatus = HandlerStatus.InjectFftDataFailed;
					} else if (messageType.equals("InjectParameterDataResponse")) {
						handlerStatus = HandlerStatus.InjectFftDataSuccessful;
					} else {
						handlerStatus = HandlerStatus.InvalidResponse;
					}
					break;

				default:
					handlerStatus = HandlerStatus.InvalidResponse;
					break;
				}

				connectionLock.notifyAll();
			}
		}

	}

	public static enum Status {

		Disconnected, Connecting, Connected, Disconnecting

	}

	public static enum HandlerStatus {

		Initializing, InitializationFinished, InvalidResponse, Terminated,
		AuthenticationTokenRequest, AuthenticationRequest, AuthenticationDenied, Authenticated,
		CreateTrackingParameters, CreateTrackingParameterSuccessful, CreateTrackingParameterFailed,
		WaitForFftData, InjectFftData, InjectFftDataSuccessful, InjectFftDataFailed

	}

	public static interface StatusListener {

		public void onStatusUpdate(Status status);

	}

}
