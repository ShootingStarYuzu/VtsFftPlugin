package me.yuzu.vts.fft;

import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import me.yuzu.vts.fft.FftService.BucketType;
import me.yuzu.vts.fft.FftService.OutputType;

public class PluginWindow extends JFrame {

	// Stupid id that nobody needs but Eclipse complains about ...
	private static final long serialVersionUID = 2366421789728363591L;

	private final Plugin plugin;

	// All the elements (buttons, labels) of the user interface.
	private final JLabel connectionUrlLabel;
	private final JTextField connectionUrlField;
	private final JLabel audioDevicesLabel;
	private final JComboBox<DeviceListEntry> audioDevicesSelect;
	private final JLabel audioTypeLabel;
	private final JComboBox<AudioTypeListEntry> audioTypeSelect;
	private final JLabel bucketsLabel;
	private final JTextField bucketsTextField;
	private final JLabel startFrequencyLabel;
	private final JTextField startFrequencyTextField;
	private final JLabel endFrequencyLabel;
	private final JTextField endFrequencyTextField;
	private final JLabel volumeLabel;
	private final JSlider volumeSlider;
	private final JLabel noiseFloorLabel;
	private final JSlider noiseFloorSlider;
	private final JLabel volumeNormalizationTypeLabel;
	private final JComboBox<OutputNormalizationTypeListEntry> volumeNormalizationTypeSelect;
	private final JLabel bucketNormalizationTypeLabel;
	private final JComboBox<BucketNormalizationTypeListEntry> bucketNormalizationTypeSelect;
	private final JLabel statusLabel;
	private final JLabel statusText;
	private final JButton startStopButton;

	public PluginWindow(Plugin plugin) {
		this.plugin = plugin;

		setTitle("VTS Audio Spectrum Plugin");
		setMinimumSize(new Dimension(400, 300));
		setIconImages(Plugin.getPluginIconImages());

		// Configure the window to automatically stopping all services of the plug-in
		// when being closed by the user.
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				plugin.stopPlugin();
			}
		});

		// Initialize all user elements
		connectionUrlLabel = new JLabel("Connection");
		connectionUrlField = new JTextField();
		audioDevicesLabel = new JLabel("Audio Device");
		audioDevicesSelect = new JComboBox<>();
		audioTypeLabel = new JLabel("Audio Type");
		audioTypeSelect = new JComboBox<>();
		audioTypeSelect.addItem(new AudioTypeListEntry(true));
		audioTypeSelect.addItem(new AudioTypeListEntry(false));
		bucketsLabel = new JLabel("Buckets");
		bucketsTextField = new JTextField();
		startFrequencyLabel = new JLabel("Start Frequency");
		startFrequencyTextField = new JTextField();
		endFrequencyLabel = new JLabel("End Frequency");
		endFrequencyTextField = new JTextField();
		volumeLabel = new JLabel("Volume");
		volumeSlider = new JSlider(0, 1000000);
		noiseFloorLabel = new JLabel("Noise Floor");
		noiseFloorSlider = new JSlider(0, 1000000);
		volumeNormalizationTypeLabel = new JLabel("Volume Norm.");
		volumeNormalizationTypeSelect = new JComboBox<>();
		volumeNormalizationTypeSelect.addItem(new OutputNormalizationTypeListEntry(OutputType.LINEAR));
		volumeNormalizationTypeSelect.addItem(new OutputNormalizationTypeListEntry(OutputType.LOGARITHMIC));
		bucketNormalizationTypeLabel = new JLabel("Bucket Norm.");
		bucketNormalizationTypeSelect = new JComboBox<>();
		bucketNormalizationTypeSelect.addItem(new BucketNormalizationTypeListEntry(BucketType.LINEAR));
		bucketNormalizationTypeSelect.addItem(new BucketNormalizationTypeListEntry(BucketType.LOGARITHMIC));

		statusLabel = new JLabel("Status");
		statusText = new JLabel("Idle");
		startStopButton = new JButton("Start");
		updateStatusField();

		// Initialize the layout and then add functionality to
		// buttons, sliders and text fields.
		initializeLayout();
		registerInternalEvents();

		// Compute the minimum size of the window and shrink it to fit
		// these dimensions.
		pack();
	}

	public final void initializeLayout() {
		GridBagConstraints c;

		// The GridBagLayout functions basically the same as
		// an Excel table in MS office. It has cells and some cells
		// can span multiple rows and columns.
		setLayout(new GridBagLayout());

		// The GridBagConstraints define which cell to place the
		// element into and how much spacing to other elements is required.
		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(5, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 0;
		add(connectionUrlLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(5, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 0;
		add(connectionUrlField, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 1;
		add(audioDevicesLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 1;
		add(audioDevicesSelect, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 2;
		add(audioTypeLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 2;
		add(audioTypeSelect, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 3;
		add(bucketsLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 3;
		add(bucketsTextField, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 4;
		add(startFrequencyLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 4;
		add(startFrequencyTextField, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 5;
		add(endFrequencyLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 5;
		add(endFrequencyTextField, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 6;
		add(volumeLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 6;
		add(volumeSlider, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 7;
		add(noiseFloorLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 7;
		add(noiseFloorSlider, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 8;
		add(volumeNormalizationTypeLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 8;
		add(volumeNormalizationTypeSelect, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 9;
		add(bucketNormalizationTypeLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 9;
		add(bucketNormalizationTypeSelect, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 5, 3, 3);
		c.gridx = 0;
		c.gridy = 10;
		add(statusLabel, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.insets = new Insets(3, 3, 3, 5);
		c.gridx = 1;
		c.gridy = 10;
		add(statusText, c);

		c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 1.0d;
		c.weighty = 1.0d;
		c.gridwidth = 2;
		c.insets = new Insets(3, 5, 5, 5);
		c.gridx = 0;
		c.gridy = 11;
		add(startStopButton, c);
	}

	private final void registerInternalEvents() {
		// Add listeners that update settings when the user changes them.
		connectionUrlField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void removeUpdate(DocumentEvent e) { updateConnectionUrl(); }
			@Override public void insertUpdate(DocumentEvent e) { updateConnectionUrl(); }
			@Override public void changedUpdate(DocumentEvent e) { updateConnectionUrl(); }
		});
		audioDevicesSelect.addActionListener(event -> updateAudioDevice());
		audioTypeSelect.addActionListener(event -> updateAudioType());
		bucketsTextField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void removeUpdate(DocumentEvent e) { updateBucketCount(); }
			@Override public void insertUpdate(DocumentEvent e) { updateBucketCount(); }
			@Override public void changedUpdate(DocumentEvent e) { updateBucketCount(); }
		});
		startFrequencyTextField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void removeUpdate(DocumentEvent e) { updateStartFrequency(); }
			@Override public void insertUpdate(DocumentEvent e) { updateStartFrequency(); }
			@Override public void changedUpdate(DocumentEvent e) { updateStartFrequency(); }
		});
		endFrequencyTextField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void removeUpdate(DocumentEvent e) { updateEndFrequency(); }
			@Override public void insertUpdate(DocumentEvent e) { updateEndFrequency(); }
			@Override public void changedUpdate(DocumentEvent e) { updateEndFrequency(); }
		});
		volumeSlider.addChangeListener(e -> updateVolume());
		noiseFloorSlider.addChangeListener(e -> updateNoiseFloor());
		volumeNormalizationTypeSelect.addActionListener(e -> updateVolumeNormalization());
		bucketNormalizationTypeSelect.addActionListener(e -> updateBucketNormalization());
		startStopButton.addActionListener(event -> onStartStopPressed());
	}

	private final void updateAudioDevice() {
		final DeviceListEntry device = (DeviceListEntry) audioDevicesSelect.getSelectedItem();
		if (device != null) {
			plugin.getSettings().audioDevice = device.getDevice().getName();
		}
	}

	private final void updateAudioType() {
		final AudioTypeListEntry type = (AudioTypeListEntry) audioTypeSelect.getSelectedItem();
		if (type != null) {
			plugin.getSettings().stereo = type.isStereo();
		}
	}

	private final void updateConnectionUrl() {
		plugin.getSettings().connectionUrl = connectionUrlField.getText();
	}

	private final void updateBucketCount() {
		try {
			plugin.getSettings().fftBuckets = Integer.parseInt(bucketsTextField.getText());
		} catch (NumberFormatException formatException) {}
	}

	private final void updateStartFrequency() {
		try {
			int frequency = Integer.parseInt(startFrequencyTextField.getText());
			if (frequency > 0) {
				plugin.getSettings().frequencyStart = frequency;
			}
		} catch (NumberFormatException formatException) {}
	}

	private final void updateEndFrequency() {
		try {
			int frequency = Integer.parseInt(endFrequencyTextField.getText());
			if (frequency < 22100) {
				plugin.getSettings().frequencyEnd = frequency;
			}
		} catch (NumberFormatException formatException) {}
	}

	private final void updateVolume() {
		float factor = (volumeSlider.getValue() - 500000.0f) / 100000.0f;
		plugin.getSettings().volume = Math.pow(10.0f, factor);
	}

	private final void updateNoiseFloor() {
		float factor = (noiseFloorSlider.getValue() - 500000.0f) / 100000.0f;
		plugin.getSettings().noiseFloor = Math.pow(10.0f, factor);
	}

	private final void updateVolumeNormalization() {
		final OutputNormalizationTypeListEntry entry = (OutputNormalizationTypeListEntry) volumeNormalizationTypeSelect.getSelectedItem();
		if (entry != null) {
			plugin.getSettings().outputType = entry.getType();
		}
	}

	private final void updateBucketNormalization() {
		final BucketNormalizationTypeListEntry entry = (BucketNormalizationTypeListEntry) bucketNormalizationTypeSelect.getSelectedItem();
		if (entry != null) {
			plugin.getSettings().bucketType = entry.getType();
		}
	}

	public void applySettings() {
		final Settings settings = plugin.getSettings();

		// Reads the settings from the plug-in and sets the user elements to that setting values.
		connectionUrlField.setText(settings.connectionUrl);
		bucketsTextField.setText(String.valueOf(settings.fftBuckets));
		startFrequencyTextField.setText(String.valueOf(settings.frequencyStart));
		endFrequencyTextField.setText(String.valueOf(settings.frequencyEnd));

		final double volume = settings.volume;
		final double volumePosition = Math.min(5.0d, Math.max(-5.0d, Math.log(volume) / Math.log(10.0f)));
		volumeSlider.setValue((int) (volumePosition * 100000.0d + 500000.0d));

		final double noiseFloor = settings.noiseFloor;
		final double noiseFloorPosition = Math.min(5.0d, Math.max(-5.0d, Math.log(noiseFloor) / Math.log(10.0f)));
		noiseFloorSlider.setValue((int) (noiseFloorPosition * 100000.0d + 500000.0d));

		for (int index = 0; index < audioDevicesSelect.getItemCount(); index++) {
			final DeviceListEntry device = audioDevicesSelect.getItemAt(index);
			if (device.getDevice().getName().equals(settings.audioDevice)) {
				audioDevicesSelect.setSelectedItem(device);
			}
		}
		for (int index = 0; index < audioTypeSelect.getItemCount(); index++) {
			final AudioTypeListEntry type = audioTypeSelect.getItemAt(index);
			if (type.isStereo() == settings.stereo) {
				audioTypeSelect.setSelectedItem(type);
			}
		}
		for (int index = 0; index < volumeNormalizationTypeSelect.getItemCount(); index++) {
			final OutputNormalizationTypeListEntry entry = volumeNormalizationTypeSelect.getItemAt(index);
			if (entry.getType().equals(settings.outputType)) {
				volumeNormalizationTypeSelect.setSelectedItem(entry);
			}
		}
		for (int index = 0; index < bucketNormalizationTypeSelect.getItemCount(); index++) {
			final BucketNormalizationTypeListEntry entry = bucketNormalizationTypeSelect.getItemAt(index);
			if (entry.getType().equals(settings.bucketType)) {
				bucketNormalizationTypeSelect.setSelectedItem(entry);
			}
		}
	}

	public final void registerEventHandler() {
		plugin.getFftService().addStatusListener(status -> updateStatusField());
		plugin.getVtsService().addStatusListener(status -> updateStatusField());

		plugin.getFftService().addAudioDeviceListener(() -> {
			final List<FftService.DeviceInfo> devices = plugin.getFftService().getAudioDevices();

			// Keep a copy of the selected audio device as it is changed when adding
			// and removing items form the combo box.
			final String selectedDevice = plugin.getSettings().audioDevice;

			synchronized (PluginWindow.this) {
				audioDevicesSelect.removeAllItems();

				int index = 1;
				for (FftService.DeviceInfo device : devices) {
					final DeviceListEntry item = new DeviceListEntry(device, index++);
					audioDevicesSelect.addItem(item);

					if (device.getName().equals(selectedDevice)) {
						audioDevicesSelect.setSelectedItem(item);
					}
				}
			}
		});
	}

	private final void onStartStopPressed() {
		final Settings settings = plugin.getSettings();
		final VtsService vtsService = plugin.getVtsService();
		final FftService fftService = plugin.getFftService();

		// If we are already modifying the audio device then do nothing
		// and don't interfere.
		switch (plugin.getVtsService().getConnectionStatus()) {
		case Connecting:
		case Disconnecting:
			return;

		// Do the start / stop attempt in another thread as it is blocking
		// and we don't want the user interface to freeze up.
		// Unlike many other single-threaded applications that freeze when
		// clicking buttons.
		case Connected:
			new Thread(() -> {
				runOrShowError(() -> fftService.stop(), "Could not stop Audio Device: ");
				runOrShowError(() -> vtsService.disconnect(), "Could not stop connection with VTS API: ");
			}).start();
			break;

		case Disconnected:
			// Do basic sanity checks before attempting to connect.
			final DeviceListEntry device = (DeviceListEntry) audioDevicesSelect.getSelectedItem();
			if (device == null) {
				JOptionPane.showMessageDialog(this, "No audio device has been selected!");
				break;
			}

			new Thread(() -> {
				if (runOrShowError(() -> vtsService.connect(settings), "Could not connect to VTS API: ")) {
					runOrShowError(() -> fftService.start(device.getDevice(), settings), "Could not start Audio Device: ");
				}
			}).start();
			break;
		}
	}

	private final void updateStatusField() {
		final VtsService vtsService = plugin.getVtsService();
		final FftService fftService = plugin.getFftService();
		final VtsService.Status vtsStatus = vtsService.getConnectionStatus();
		final FftService.Status fftStatus = fftService.getConnectionStatus();

		EventQueue.invokeLater(() -> {
			statusText.setText(String.format("VTS Connection: %s, Audio Device: %s", vtsStatus.name(), fftStatus.name()));
			startStopButton.setEnabled(vtsStatus == VtsService.Status.Disconnected || vtsStatus == VtsService.Status.Connected);
			connectionUrlField.setEnabled(vtsStatus == VtsService.Status.Disconnected);
			bucketsTextField.setEnabled(vtsStatus == VtsService.Status.Disconnected);
			audioDevicesSelect.setEnabled(vtsStatus == VtsService.Status.Disconnected);
			audioTypeSelect.setEnabled(vtsStatus == VtsService.Status.Disconnected);
			startFrequencyTextField.setEnabled(vtsStatus == VtsService.Status.Disconnected);
			endFrequencyTextField.setEnabled(vtsStatus == VtsService.Status.Disconnected);
			bucketNormalizationTypeSelect.setEnabled(vtsStatus == VtsService.Status.Disconnected);

			switch (vtsStatus) {
			case Disconnected:
				startStopButton.setText("Connect");
				break;
			case Connecting:
				startStopButton.setText("Connecting");
				break;
			case Connected:
				startStopButton.setText("Disconnect");
				break;
			case Disconnecting:
				startStopButton.setText("Disconnecting");
				break;
			}
		});
	}

	private final boolean runOrShowError(RunableWithIOException runnable, String prefix) {
		try {
			runnable.run();
			return true;
		} catch (IOException ioException) {
			ioException.printStackTrace(System.err);
			JOptionPane.showMessageDialog(this, prefix + ioException);
			return false;
		}
	}

	public interface RunableWithIOException {

		public void run() throws IOException;

	}

	private static class DeviceListEntry {

		private final FftService.DeviceInfo device;
		private final int index;

		public DeviceListEntry(FftService.DeviceInfo device, int index) {
			this.device = device;
			this.index = index;
		}

		public final FftService.DeviceInfo getDevice() {
			return device;
		}

		@Override
		public final String toString() {
			return device.getName();
			//return String.format("Device %d", index);
		}

	}

	private static class AudioTypeListEntry {

		private final boolean stereo;

		public AudioTypeListEntry(boolean stereo) {
			this.stereo = stereo;
		}

		public final boolean isStereo() {
			return stereo;
		}

		@Override
		public String toString() {
			return stereo ? "Stereo" : "Mono";
		}

	}

	private static class OutputNormalizationTypeListEntry {

		private final OutputType normalizationType;

		public OutputNormalizationTypeListEntry(OutputType normalizationType) {
			this.normalizationType = normalizationType;
		}

		public OutputType getType() {
			return normalizationType;
		}

		@Override
		public String toString() {
			switch (normalizationType) {
			case LINEAR:
				return "Linear";
			case LOGARITHMIC:
				return "Logarithmic";
			default:
				return "Unknown";
			}
		}

	}

	private static class BucketNormalizationTypeListEntry {

		private final BucketType normalizationType;

		public BucketNormalizationTypeListEntry(BucketType normalizationType) {
			this.normalizationType = normalizationType;
		}

		public BucketType getType() {
			return normalizationType;
		}

		@Override
		public String toString() {
			switch (normalizationType) {
			case LINEAR:
				return "Linear";
			case LOGARITHMIC:
				return "Logarithmic";
			default:
				return "Unknown";
			}
		}

	}

}
