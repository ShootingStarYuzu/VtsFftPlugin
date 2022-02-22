package me.yuzu.vts.fft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import org.apache.commons.math.complex.Complex;
import org.apache.commons.math.transform.FastFourierTransformer;

public class FftService {

	private static enum FftType { SINGLE_FFT, MULTI_FFT /*, SGDFT */ }
	private static enum WindowType { NONE, HAMMING, NUTTALL }
	public static enum BucketType { LINEAR, LOGARITHMIC }
	public static enum OutputType { LINEAR, LOGARITHMIC }

	private static final FftType FFT_TYPE = FftType.MULTI_FFT;
	private static final WindowType WINDOW_TYPE = WindowType.NUTTALL;
	private static final int MULTI_FFT_COUNT = (16 - 5); // 2^16 (65536) samples to 2^4 (16) samples

	private final List<StatusListener> statusListener;
	private final List<DeviceInfoUpdateListener> deviceListener;
	private final List<FftDataListener> fftListener;

	private final Object devicesLock;
	private final List<Mixer> mixers;
	private final List<DeviceInfo> devices;

	private final Object connectionLock;
	private volatile Status connectionStatus;
	private volatile DeviceInfo connectedDevice;
	private volatile Thread connectionThread;
	private volatile IOException connectionError;
	private volatile TargetDataLine connectionDataLine;

	public FftService() {
		this.statusListener = new ArrayList<>();
		this.deviceListener = new ArrayList<>();
		this.fftListener = new ArrayList<>();

		this.devicesLock = new Object();
		this.mixers = new ArrayList<>();
		this.devices = new ArrayList<>();

		this.connectionLock = new Object();
		this.connectionStatus = Status.Disconnected;
		this.connectedDevice = null;
		this.connectionThread = null;
		this.connectionError = null;
		this.connectionDataLine = null;
	}

	public final void searchAudioDevices() {
		synchronized (devicesLock) {
			// Remove old mixers and old lines
			for (final Mixer mixer : this.mixers) {
				mixer.close();
			}
			this.mixers.clear();
			this.devices.clear();

			final Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
			for (final Mixer.Info mixerInfo: mixerInfos) {
				final Mixer mixer = AudioSystem.getMixer(mixerInfo);

				// Documentation: "Mixers might require to be opened for lines to be available".
				if (!mixer.isOpen()) {
					try {
						mixer.open();
					} catch (LineUnavailableException exception) {
						System.err.println("Could not open mixer " + mixerInfo.getName());
						exception.printStackTrace(System.err);
						continue;
					}
				}

				// Find mixers that we can actually use.
				final AudioFormat[] monoAudioFormats = createSupportedAudioFormats(1);
				final AudioFormat[] stereoAudioFormats = createSupportedAudioFormats(2);

				boolean supported = false;
				for (final AudioFormat audioFormat : monoAudioFormats) {
					if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, audioFormat))) {
						supported = true;
						break;
					}
				}
				for (final AudioFormat audioFormat : stereoAudioFormats) {
					if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, audioFormat))) {
						supported = true;
						break;
					}
				}

				if (!supported) {
					continue;
				}

				this.mixers.add(mixer);
				this.devices.add(new DeviceInfo(mixer));
			}
		}

		for (final DeviceInfoUpdateListener listener : this.deviceListener) {
			if (listener != null) {
				listener.onDevicesUpdated();
			}
		}
	}

	public final List<DeviceInfo> getAudioDevices() {
		synchronized (this.devicesLock) {
			return new ArrayList<>(this.devices);
		}
	}

	public final DeviceInfo getConnectedDevice() {
		return connectedDevice;
	}

	public final Status getConnectionStatus() {
		return connectionStatus;
	}

	public final void start(DeviceInfo device, Settings settings) throws IOException {
		synchronized (connectionLock) {
			// Finish old connection attempt that might be in progress.
			if (connectionStatus == Status.Disconnecting) {
				waitWhileConnectionStatusIs(Status.Disconnecting);
			} else if (connectionStatus == Status.Connecting || connectionStatus == Status.Connected) {
				setConnectionStatus(Status.Disconnecting);
				connectionThread.interrupt();
				waitWhileConnectionStatusIs(Status.Disconnecting);
			}

			// Start a new connection attempt.
			setConnectionStatus(Status.Connecting);
			connectionThread = new Thread(() -> run(device, settings));
			connectionThread.start();
			waitWhileConnectionStatusIs(Status.Connecting);

			// If connectionError is not null then the other thread encountered
			// an error during the connection process.
			if (connectionError != null) {
				throw connectionError;
			}
		}
	}

	public final void stop() throws IOException {
		synchronized (connectionLock) {
			// Finish old disconnect attempt that might be in progress.
			if (connectionStatus == Status.Disconnecting) {
				waitWhileConnectionStatusIs(Status.Disconnecting);
			} else if (connectionStatus == Status.Connecting || connectionStatus == Status.Connected) {
				// Signal the other thread to disconnect.
				setConnectionStatus(Status.Disconnecting);
				connectionThread.interrupt();
				if (connectionDataLine != null) {
					connectionDataLine.close();
				}
				waitWhileConnectionStatusIs(Status.Disconnecting);
			}

			// If connectionError is not null then the other thread encountered
			// an error during the disconnection process.
			if (connectionError != null) {
				throw connectionError;
			}
		}
	}

	private final void waitWhileConnectionStatusIs(Status status) {
		while (connectionStatus == status) {
			try {
				// Wait on the lock such that the other thread
				// can notify this thread of changes.
				connectionLock.wait();
			} catch (InterruptedException exception) { }
		}
	}

	private final void run(DeviceInfo deviceInfo, Settings settings) {
		IOException error = null;
		try {
			final int buckets = settings.fftBuckets;
			final int channels = settings.stereo ? 2 : 1;
			final AudioFormat[] audioFormats = createSupportedAudioFormats(channels);

			// Find the correct options to open this audio device.
			TargetDataLine targetDataLine = null;
			for (final AudioFormat audioFormat : audioFormats) {
				final DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, audioFormat);
				if (!deviceInfo.getMixer().isLineSupported(lineInfo)) {
					continue;
				}

				try {
					targetDataLine = (TargetDataLine) AudioSystem.getTargetDataLine(audioFormat, deviceInfo.getMixer().getMixerInfo());
					targetDataLine.open(audioFormat, (int) (audioFormat.getChannels() * audioFormat.getFrameRate() * 0.1)); // 100 ms buffer
				} catch (IllegalArgumentException illegalArgumentException) {
					continue;
				}
				break;
			}

			// If the target data line is null then we were not able to open it.
			// Fail the connection in this case and set the error message accordingly.
			if (targetDataLine == null) {
				synchronized (connectionLock) {
					// Check if the connection attempt was already cancelled.
					// Only if we still want to start then we change the status to started.
					// Otherwise we will drop through the loop below and set the status
					// immediately to idle.
					if (connectionStatus == Status.Connecting) {
						error = new IOException("Could not open the device in any supported audio format!");
					}

					return;
				}
			}

			// We opened the target data line and so it's now time to notify the other
			// thread of the connection change.
			targetDataLine.start();
			synchronized (connectionLock) {
				// Check if the connection attempt was already cancelled.
				// Only if we still want to start then we change the status to started.
				// Otherwise we will drop through the loop below and set the status
				// immediately to idle.
				if (connectionStatus == Status.Connecting) {
					connectedDevice = deviceInfo;
					connectionDataLine = targetDataLine;
					connectionError = null;
					setConnectionStatus(Status.Connected);
					connectionLock.notifyAll();
				}
			}

			// Store some settings in variables here to make them immutable.
			final int frequencyStart = settings.frequencyStart;
			final int frequencyEnd = settings.frequencyEnd;
			final BucketType bucketType = settings.bucketType;
			final AudioFormat audioFormat = targetDataLine.getFormat();
			final int bytesPerFrame = audioFormat.getFrameSize();
			final float frameRate = audioFormat.getFrameRate();

			// Data size has to be a power of two for the FFT to work.
			final float[][] fftInputData = new float[channels][];
			final float[][] peaks = new float[channels][];
			for (int channel = 0; channel < channels; channel++) {
				fftInputData[channel] = new float[65536];
				peaks[channel] = new float[buckets];
			}

			// Create a lot of data structures to store the audio data.
			final double[][] fftSingleWindowedData = new double[channels][];
			final int[] fftSingleFrequencyRange = new int[2];
			final double[][][] fftMultiWindowedData = new double[channels][][];
			final int[][] fftMultiFrequencyRanges = new int[MULTI_FFT_COUNT][];
			//final int[] sgdftFrequencies = new int[buckets];
			//final double[][] sgdftResults = new double[channels][];
			//final double[][] sgdftResultsDelayed = new double[channels][];

			final int fftInputDataSize = 65536; // Can hold at least 22100 * 2 samples for full spectrum
			switch (FFT_TYPE) {
			case SINGLE_FFT:
				fftSingleFrequencyRange[0] = 1;
				fftSingleFrequencyRange[1] = fftInputDataSize / 2;
				for (int channel = 0; channel < channels; channel++) {
					fftSingleWindowedData[channel] = new double[fftInputDataSize];
				}

				System.out.println(String.format("1. FFT with %d samples for frequency %d Hz to %d Hz",
						fftSingleWindowedData[0].length, fftSingleFrequencyRange[0], fftSingleFrequencyRange[1]));
				break;

			case MULTI_FFT:
				for (int size = 0; size < MULTI_FFT_COUNT; size++) {
					fftMultiFrequencyRanges[size] = new int[] {
							(int) Math.pow(2.0d, size), // Start frequency
							fftInputDataSize // End frequency
						};
				}

				for (int channel = 0; channel < channels; channel++) {
					fftMultiWindowedData[channel] = new double[MULTI_FFT_COUNT][];
					for (int size = 0; size < MULTI_FFT_COUNT; size++) {
						// Can hold at least 22100 * 2 / 2^(divider_max-divider) samples to calculate each sub-spectrum
						fftMultiWindowedData[channel][size] = new double[fftInputDataSize / (int) Math.pow(2.0d, size)];
					}
				}

				// Frequency resolution is inversely proportional to frequency.
				for (int size = 0; size < MULTI_FFT_COUNT; size++) {
					System.out.println(String.format("%d. FFT with %d samples for frequency %d Hz to %d Hz",
							size + 1, fftMultiWindowedData[0][size].length, fftMultiFrequencyRanges[size][0], fftMultiFrequencyRanges[size][1]));
				}
				break;

			/*
			case SGDFT:
				// Initialize filters for buckets
				for (int bucket = 0; bucket < buckets; bucket++) {
					sgdftFrequencies[bucket] = getFrequencyForBucket(bucketType, buckets, bucket, frequencyStart, frequencyEnd);
				}
				for (int channel = 0; channel < channels; channel++) {
					sgdftResults[channel] = new double[buckets];
					sgdftResultsDelayed[channel] = new double[buckets];
				}
				break;
			*/

			default:
				throw new IllegalArgumentException("Unsupported FFT type");

			}

			// The window size is the data that is read from the audio stream
			// in each loop.
			final int windowSize = 1024;
			final FastFourierTransformer fft = new FastFourierTransformer();
			final byte[] buffer = new byte[windowSize * bytesPerFrame];

			int fftInputDataOffset = 0;
			long lastSampleTime = System.currentTimeMillis();
			long sampleDelay = (long) (1.0d / frameRate * windowSize * 1000.0d);

			// This is the main loop that reads and processes the audio data.
			// The audio data is analyzed by appending it to an audio buffer which is
			// used in a sliding window like fashion.
			while (!Thread.interrupted() && connectionStatus != Status.Disconnecting) {
				// Read signed PCM frames and convert to float values.
				targetDataLine.read(buffer, 0, buffer.length);
				convertSampleDataToFloat(audioFormat, buffer, fftInputData, fftInputDataOffset);

				final int fftInputDataEnd = fftInputDataOffset + windowSize;
				final FftData fftData = new FftData(frequencyStart, frequencyEnd, channels, buckets);

				switch (FFT_TYPE) {
				case SINGLE_FFT:
					// Using a single Fast Fourier Transformation (FFT) results in that
					// the output value for each frequency bin is averaged over the time window.
					// The time window determines the range of frequencies that can be analyzed.
					// A better approach is to use multiple Fast Fourier Transformations (FFTs)
					// such that higher frequencies can be analyzed with shorter time windows.

					// Apply the Fast Fourier transformation to get the frequency data.
					for (int channel = 0; channel < channels; channel++) {
						// Apply a window function to the input data such that the border conditions do not introduce
						// spurious frequencies into the FF transformation.
						final int fftCopyRange = fftSingleWindowedData[channel].length;
						copyNthWindowedSample(fftInputData[channel], fftSingleWindowedData[channel], fftInputDataEnd - fftCopyRange);

						final Complex[] fftOutput = fft.transform(fftSingleWindowedData[channel]);

						// Now sort the output data into the frequency buckets.
						for (int frequency = frequencyStart; frequency < frequencyEnd; frequency++) {
							if (frequency < fftSingleFrequencyRange[0] || frequency >= fftSingleFrequencyRange[1]) {
								continue;
							}

							final int bucket = (frequency - frequencyStart) * buckets / (frequencyEnd - frequencyStart);
							fftData.level[channel][bucket] += fftOutput[(frequency - fftSingleFrequencyRange[0]) / fftSingleFrequencyRange[0]].abs();
							fftData.samples[channel][bucket]++;
						}
					}
					break;

				case MULTI_FFT:
					// Using multiple Fast Fourier Transformations (FFTs) increases the time resolution
					// for higher frequencies. This costs additional computation time and with that is
					// a tradeoff between quality and computation time.

					// Apply the Fast Fourier transformation to get the frequency data.
					for (int channel = 0; channel < channels; channel++) {
						for (int size = 0; size < MULTI_FFT_COUNT; size++) {
							// Apply a window function to the input data such that the border conditions do not introduce
							// spurious frequencies into the FF transformation.
							final int fftCopyRange = fftMultiWindowedData[channel][size].length;
							copyNthWindowedSample(fftInputData[channel], fftMultiWindowedData[channel][size], fftInputDataEnd - fftCopyRange);

							final Complex[] fftOutput = fft.transform(fftMultiWindowedData[channel][size]);
							for (int frequency = frequencyStart; frequency < frequencyEnd; frequency++) {
								if (frequency < fftMultiFrequencyRanges[size][0] || frequency >= fftMultiFrequencyRanges[size][1]) {
									continue;
								}

								// Normalize the frequency spectrum that is divided over multiple buckets.
								final int bucket = getBucketForFrequency(bucketType, buckets, frequencyStart, frequencyEnd, frequency);
								fftData.level[channel][bucket] += fftOutput[(frequency - fftMultiFrequencyRanges[size][0]) / fftMultiFrequencyRanges[size][0]].abs();
								fftData.samples[channel][bucket]++;
							}
						}
					}
					break;

				/*
				case SGDFT:
					// Warning: This code is not functional at all.
					// TODO: Find out why this does not work at all.

					// The Sliding Goertzel Discrete Fourier Transformation (SGDFT) can be used
					// to do a Fourier transformation on streaming input data.
					// It has the benefit of being able to be applied to a sliding window which
					// means that not all data has to be reprocessed all the time.
					// Instead only the new data is processed and which reduces the total
					// computation time in some cases.
					// See the following resources for more information about applications:
					// - https://ieeexplore.ieee.org/document/4488619
					// - https://ieeexplore.ieee.org/document/9358492
					// - https://www.intechopen.com/chapters/54042
					//
					// In theory this should be faster, but I'm too stupid to write the code correctly. (-'_'-)

					final double r = 0.999;
					for (int channel = 0; channel < channels; channel++) {
						for (int bucket = 0; bucket < buckets; bucket++) {
							for (int offset = 0; offset < windowSize; offset++) {
								final int N = (int) (frameRate / sgdftFrequencies[bucket]);
								final double vNminus1 = sgdftResults[channel][bucket];
								final double vNminus2 = sgdftResultsDelayed[channel][bucket];
								final double xN = fftInputData[channel][fftInputDataOffset + offset];
								final double XNminusN = fftInputData[channel][(fftInputDataOffset + offset - N + fftInputData[channel].length) % fftInputData[channel].length];
								sgdftResultsDelayed[channel][bucket] = sgdftResults[channel][bucket];
								sgdftResults[channel][bucket] = 2.0d * r * Math.cos(2.0d * Math.PI / N) * vNminus1 - r * r * vNminus2
										+ xN - Math.pow(r, N) * XNminusN;
							}

							fftData.level[channel][bucket] = (float) (sgdftResults[channel][bucket] - r * sgdftResultsDelayed[channel][bucket]);
							fftData.samples[channel][bucket]++;
						}
					}
					break;
					*/

				default:
					throw new IllegalArgumentException("Unsupported FFT type");

				}

				final double volume = settings.volume;
				final double noiseFloor = settings.noiseFloor;
				final OutputType outputType = settings.outputType;
				for (int channel = 0; channel < channels; channel++) {
					for (int bucket = 0; bucket < buckets; bucket++) {
						fftData.level[channel][bucket] /= (float) fftData.samples[channel][bucket];

						fftData.level[channel][bucket] = Math.max(Math.min(scaleOutput(outputType, volume, noiseFloor, fftData.level[channel][bucket]), 0.99f), 0.0f);
						fftData.peak[channel][bucket] = Math.max(peaks[channel][bucket] * 0.95f, fftData.level[channel][bucket]);
						peaks[channel][bucket] = fftData.peak[channel][bucket];
					}
				}

				// Try to not stream all samples at the same time but create a smooth steady animation
				// by delaying the samples if they are read too fast.
				// This can happen since we are reading the input audio data in batches of "windowSize".
				// Use a loop here since Thread.sleep might wake up without reason before the delay ended.
				// long totalDelay = 0;
				while (true) {
					long delay = (System.currentTimeMillis() - lastSampleTime);
					// Allow to be slightly faster to account for small delays elsewhere
					if (delay >= sampleDelay * 0.85) {
						break;
					}

					// totalDelay += sampleDelay - delay;
					try {
						Thread.sleep(sampleDelay - delay);
					} catch (InterruptedException e) { }
				}
				// System.out.println(String.format("Sample time %03.2f%% (%d ms delayed for realtime)", (0.1f * (System.currentTimeMillis() - lastSampleTime) / (windowSize / frameRate)), totalDelay));
				lastSampleTime = System.currentTimeMillis();

				// Notify the event listeners about the new FFT data.
				for (FftDataListener listener : fftListener) {
					if (listener != null) {
						listener.onFftData(fftData);
					}
				}

				// Advance the sliding window.
				fftInputDataOffset += windowSize;
				fftInputDataOffset %= fftInputData[0].length;
			}

			// We are done so stop everything.
			targetDataLine.stop();
			targetDataLine.flush();
			targetDataLine.close();
		} catch (LineUnavailableException lineUnavailableException) {
			error = new IOException("Audio device was not ready to be opened!", lineUnavailableException);
		} finally {
			// When everything is finished notify the other thread of the change
			// if that did not already happen.
			synchronized (connectionLock) {
				if (connectionStatus != Status.Disconnected) {
					connectedDevice = null;
					connectionThread = null;
					connectionError = error;
					setConnectionStatus(Status.Disconnected);
					connectionLock.notifyAll();
				}
			}
		}
	}

	private final void convertSampleDataToFloat(AudioFormat format, byte[] inputData, float[][] outputData, int outputStartIndex) {
		final int bitsPerSample = format.getSampleSizeInBits();
		final int channels = format.getChannels();

		int outputOffset = 0;
		int inputOffset = 0;

		// Converts each audio sample by reading frame by frame and converting all contained channels.
		while (inputOffset < inputData.length) {
			final int outputIndex = (outputStartIndex + outputOffset++) % outputData[0].length;

			switch (bitsPerSample) {
			case 8:
				// Signed PCM 8 bit little-endian: [sbyte]
				for (int channel = 0; channel < channels; channel++) {
					outputData[channel][outputIndex] = (float) (
							((       ((int) inputData[inputOffset++]) <<  0))
						) / 128.0f;
				}
				break;

			case 16:
				// Signed PCM 16 bit little-endian: [sbyte][byte]
				for (int channel = 0; channel < channels; channel++) {
					outputData[channel][outputIndex] = (float) (
							((0xFF & ((int) inputData[inputOffset++]) <<  0)) |
							((       ((int) inputData[inputOffset++]) <<  8))
						) / (128.0f * 256.0f);
				}
				break;

			case 24:
				// Signed PCM 16 bit little-endian: [sbyte][byte][byte]
				for (int channel = 0; channel < channels; channel++) {
					outputData[channel][outputIndex] = (float) (
							((0xFF & ((int) inputData[inputOffset++]) <<  0)) |
							((0xFF & ((int) inputData[inputOffset++]) <<  8)) |
							((       ((int) inputData[inputOffset++]) << 16))
						) / (128.0f * 256.0f * 256.0f);
				}
				break;

			case 32:
				// Signed PCM 32 bit little-endian: [sbyte][byte][byte][byte]
				for (int channel = 0; channel < channels; channel++) {
					outputData[channel][outputIndex] = (float) (
							((0xFF & ((int) inputData[inputOffset++]) <<  0)) |
							((0xFF & ((int) inputData[inputOffset++]) <<  8)) |
							((0xFF & ((int) inputData[inputOffset++]) << 16)) |
							((       ((int) inputData[inputOffset++]) << 24))
						) / (128.0f * 256.0f * 256.0f * 256.0f);
				}
				break;
			}
		}
	}

	private final void copyNthWindowedSample(float[] inputData, double[] outputData, int inputStartIndex) {
		// Copies the input data to the output while applying the window function.
		for (int outputIndex = 0; outputIndex < outputData.length; outputIndex++) {
			final int inputIndex = (inputData.length + inputStartIndex + outputIndex) % inputData.length;
			outputData[outputIndex] = inputData[inputIndex] * applyWindow(outputIndex, outputData.length);
		}
	}

	private final float applyWindow(int sampleFrame, int sampleFrames) {
		// See https://en.wikipedia.org/wiki/Window_function for more window functions.
		// The window function fades the input data on the start and end to silent such
		// that at the borders no step is present which would introduce additional
		// frequencies into the Fast Fourier Transformation.

		switch (WINDOW_TYPE) {
		case NONE:
			return 1.0f;

		case HAMMING:
			return 0.53836f - 0.46164f * (float) Math.cos(2.0f * Math.PI * sampleFrame / sampleFrames);

		case NUTTALL:
			return 0.355768f - 0.487396f * (float) Math.cos(2.0f * Math.PI * sampleFrame / sampleFrames)
					+ 0.144232f * (float) Math.cos(4.0f * Math.PI * sampleFrame / sampleFrames)
					- 0.012604f * (float) Math.cos(6.0f * Math.PI * sampleFrame / sampleFrames);

		default:
			throw new IllegalArgumentException("Unsupported window type");

		}
	}

	private final int getBucketForFrequency(BucketType bucketType, int buckets, int frequencyStart, int frequencyEnd, int frequency) {
		if (bucketType == null) { bucketType = BucketType.LINEAR; }

		switch (bucketType) {
		case LINEAR:
			return buckets * (frequency - frequencyStart) / (frequencyEnd - frequencyStart);

		case LOGARITHMIC:
			return (int) (buckets * (Math.log(frequency) - Math.log(frequencyStart)) / (Math.log(frequencyEnd) - Math.log(frequencyStart)));

		default:
			throw new IllegalArgumentException("Unsupported bucket type");

		}
	}

	private final int getFrequencyForBucket(BucketType bucketType, int buckets, int bucket, int frequencyStart, int frequencyEnd) {
		if (bucketType == null) { bucketType = BucketType.LINEAR; }

		switch (bucketType) {
		case LINEAR:
			return bucket * (frequencyEnd - frequencyStart) / (buckets - 1) + frequencyStart;

		case LOGARITHMIC:
			return (int) Math.pow(Math.E, buckets * (Math.log(frequencyEnd) - Math.log(frequencyStart)) / (buckets - 1)) + frequencyStart;

		default:
			throw new IllegalArgumentException("Unsupported bucket type");

		}
	}

	private static final float scaleOutput(OutputType outputType, double volume, double noiseFloor, float value) {
		if (outputType == null) { outputType = OutputType.LINEAR; }

		value *= volume;

		switch (outputType) {
		case LINEAR:
			return (float) (value - noiseFloor);

		case LOGARITHMIC:
			return (float) ((Math.log(value + 0.0000000000000000000000001d) - Math.log(0.1d) - noiseFloor) / (Math.log(1.0d) - Math.log(0.1d)));

		default:
			throw new IllegalArgumentException("Unsupported output type");

		}
	}

	private final void setConnectionStatus(Status status) {
		this.connectionStatus = status;

		for (StatusListener listener : statusListener) {
			if (listener != null) {
				listener.onStatusUpdated(status);
			}
		}
	}

	private final AudioFormat[] createSupportedAudioFormats(int channels) {
		List<AudioFormat> formats = new ArrayList<>();

		for (float sampleRate : new float[] { 44100.0f, 48000.0f, 96000.0f, 32000.0f, 22050.0f }) {
			for (int bitsPerSample : new int[] { 16, 24, 32, 8 }) {
				formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, bitsPerSample, channels, bitsPerSample * channels / 8, sampleRate, false));
			}
		}

		return formats.toArray(new AudioFormat[0]);
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

	public final void addAudioDeviceListener(DeviceInfoUpdateListener listener) {
		this.deviceListener.add(listener);
	}

	public final void removeAudioDeviceListener(DeviceInfoUpdateListener listener) {
		this.deviceListener.remove(listener);
	}

	public final void clearAudioDeviceListener() {
		this.deviceListener.clear();
	}

	public final void addFftDataListener(FftDataListener listener) {
		this.fftListener.add(listener);
	}

	public final void removeFftDataListener(FftDataListener listener) {
		this.fftListener.remove(listener);
	}

	public final void clearFftDataListener() {
		this.fftListener.clear();
	}

	public static enum Status {

		Disconnected, Connecting, Connected, Disconnecting

	}

	public static interface StatusListener {

		public void onStatusUpdated(Status status);

	}

	public static interface DeviceInfoUpdateListener {

		public void onDevicesUpdated();

	}

	public static interface FftDataListener {

		public void onFftData(FftData data);

	}

	public static class DeviceInfo {

		private final Mixer mixer;

		public DeviceInfo(Mixer mixer) {
			this.mixer = mixer;
		}

		public String getName() {
			return mixer.getMixerInfo().getName();
		}

		public Mixer getMixer() {
			return mixer;
		}

	}

	public static class FftData {

		public static final int CHANNELS_MONO = 1;
		public static final int CHANNELS_STEREO = 2;

		public static final int CHANNEL_MONO = 0;
		public static final int CHANNEL_LEFT = 0;
		public static final int CHANNEL_RIGHT = 1;

		private final float startFrequency;
		private final float endFrequency;
		private final int channels;
		private final int buckets;
		private final float[][] level;
		private final float[][] peak;
		private final int[][] samples;

		public FftData(float startFrequency, float endFrequency, int channels, int buckets) {
			this.startFrequency = startFrequency;
			this.endFrequency = endFrequency;
			this.channels = channels;
			this.buckets = buckets;

			level = new float[channels][];
			peak = new float[channels][];
			samples = new int[channels][];
			for (int channel = 0; channel < channels; channel++) {
				level[channel] = new float[buckets];
				peak[channel] = new float[buckets];
				samples[channel] = new int[buckets];
			}
		}

		public float getStartFrequency() {
			return startFrequency;
		}

		public float getEndFrequency() {
			return endFrequency;
		}

		public int getChannels() {
			return channels;
		}

		public int getBuckets() {
			return buckets;
		}

		public float getLevel(int channel, int bucket) {
			return level[channel][bucket];
		}

		public float getPeak(int channel, int bucket) {
			return peak[channel][bucket];
		}

		public float getSamples(int channel, int bucket) {
			return samples[channel][bucket];
		}

	}

}
