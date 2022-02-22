package me.yuzu.vts.fft;

import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Plugin {

	// All those resources are thrown into here because I'm lazy.
	private static final String PLUGIN_NAME = "VTS Audio Spectrum";
	private static final String PLUGIN_DEVELOPER = "Shooting Star Yuzu";
	private static final String PLUGIN_ICON =
		"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" +
		"/wHt6gDZgSFZ5QAAAABJRU5ErkJggg==";

	private static final String PLUGIN_ICON_TRANSPARENT_16x16 =
		"iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAB+klEQVQ4jZVTy2sTQRz+frOvbLba" +
		"smowByulxUfVFVFDBRE8SkEoIujBB54qBHz8ASJ4sngSvQmee6hYKXiqimiV4IOaB0pFL2LBmKSt" +
		"NJvdmZ3x0pak3Ub83X58j/nmG4bwjxm8fbjL6XRugmgIUEbEZXYs+/LRMq63E5+8M7A12Zn8BsAm" +
		"IiilykEY5Jo5rJ1BYoP9AIBNjBCJaLT6s9o9fv3Nj2YONS+VXEYHoDZlclEm2631eL2CMQYe8vsA" +
		"ZnRduwyinUopDoW3POBXWq7gJOsnNCZOAzi/eVtqO4gguPiu6/pZENyVIxUMAPuZzo61GISh9dlx" +
		"gnONwt6xM5PWHAFgjPUs5eQykncDPxitzJY/vhr5KtaU2AjsWoczD0MPHt/w/CcjMyaIEQQX12qz" +
		"1XuTtwpidU8tHdQ/eUcSVn1qef+1aANSTbi2fEGkUgpIV6ruhfTR1yrWgJd2P9Q0fnHtewBSMl8I" +
		"YzCxr/g8NoGf9zzT9KcJKk5caTTsHR0HpqurMbYk3mMajQ9xYgVCyK3jcWIAYGGx/6pp+AUAWnx0" +
		"7VnSy+fjMABgXBhPF/2NfeXKFmv+j5vmwhyWki2sJFBsYj3xujP3/pDJS7vG5Zc+FZb6L7Xjxv6F" +
		"roPvwtqCOySlVgRU738bAEBqYEqG3DylFPvdzuAvPXrMFWUA3msAAAAASUVORK5CYII=";

	private static final String PLUGIN_ICON_TRANSPARENT_32x32 =
		"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";

	private static final String PLUGIN_ICON_TRANSPARENT_48x48 =
		"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" +
		"gg==";

	private static final String PLUGIN_ICON_TRANSPARENT_128x128 =
		"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" +
		"dtlll13W4f8DQQzaILULyyUAAAAASUVORK5CYII=";

	public static String getPluginName() {
		return PLUGIN_NAME;
	}

	public static String getPluginDeveloper() {
		return PLUGIN_DEVELOPER;
	}

	public static String getPluginIconBase64() {
		return PLUGIN_ICON;
	}

	public static List<BufferedImage> getPluginIconImages() {
		final List<BufferedImage> images = new ArrayList<>();

		final BufferedImage tinyIcon = convertBase64ToImage(PLUGIN_ICON_TRANSPARENT_16x16);
		if (tinyIcon != null) images.add(tinyIcon);

		final BufferedImage smallIcon = convertBase64ToImage(PLUGIN_ICON_TRANSPARENT_32x32);
		if (smallIcon != null) images.add(smallIcon);

		final BufferedImage mediumIcon = convertBase64ToImage(PLUGIN_ICON_TRANSPARENT_48x48);
		if (mediumIcon != null) images.add(mediumIcon);

		final BufferedImage largeIcon = convertBase64ToImage(PLUGIN_ICON_TRANSPARENT_128x128);
		if (largeIcon != null) images.add(largeIcon);

		return images;
	}

	private static BufferedImage convertBase64ToImage(String base64Data) {
		final byte[] imageData = Base64.getDecoder().decode(base64Data);
		try (final ByteArrayInputStream in = new ByteArrayInputStream(imageData)) {
			return ImageIO.read(in);
		} catch (IOException exception) {
			exception.printStackTrace(System.err);
			return null;
		}
	}

	public static void main(String[] args) {
		try {
			// Adjust the look-and-feel to match the platform.
			// For example this will change the window to look like a Windows application
			// when running on Windows and like a Mac OS application when running on OS X.
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
				| UnsupportedLookAndFeelException e) {
			System.err.println("Could not set native UI desing! Using default design.");
			e.printStackTrace(System.err);
		}

		final Path pluginPath = Paths.get(".");
		final Plugin plugin = new Plugin(pluginPath);
		plugin.startPlugin();
	}

	private final Path pluginPath;
	private final Gson settingsCodec;
	private final FftService fftService;
	private final VtsService vtsService;
	private final PluginWindow fftInterface;

	private Settings settings;

	public Plugin(Path pluginPath) {
		this.pluginPath = pluginPath;

		settings = new Settings();
		settingsCodec = new GsonBuilder().setPrettyPrinting().create();
		fftService = new FftService();
		vtsService = new VtsService();
		fftInterface = new PluginWindow(this);

		fftInterface.registerEventHandler();
		fftService.addFftDataListener(data -> vtsService.queueFftData(data));
	}

	public Settings getSettings() {
		return settings;
	}

	public FftService getFftService() {
		return fftService;
	}

	public VtsService getVtsService() {
		return vtsService;
	}

	public PluginWindow getInterface() {
		return fftInterface;
	}

	public final void loadSettings() throws IOException {
		final Path settingsPath = pluginPath.resolve("config.json");
		try (Reader reader = Files.newBufferedReader(settingsPath)) {
			settings = settingsCodec.fromJson(reader, Settings.class);
		}
	}

	public final void saveSettings() throws IOException {
		final Path settingsPath = pluginPath.resolve("config.json");
		try (Writer writer = Files.newBufferedWriter(settingsPath)) {
			settingsCodec.toJson(settings, Settings.class, writer);
		}
	}

	public final void startPlugin() {
		try {
			loadSettings();
		} catch (IOException ioException) {
			System.err.println("Could not load settings. Using default values instead!");
			ioException.printStackTrace(System.err);
		}

		fftService.searchAudioDevices();
		fftInterface.applySettings();
		EventQueue.invokeLater(() -> fftInterface.setVisible(true));
	}

	public final void stopPlugin() {
		try {
			// setVisible and dispose should run in the UI thread
			// and therefore this if-case is required.
			if (!EventQueue.isDispatchThread()) {
				EventQueue.invokeAndWait(() -> {
					fftInterface.setVisible(false);
					fftInterface.dispose();
				});
			} else {
				fftInterface.setVisible(false);
				fftInterface.dispose();
			}
		} catch (InvocationTargetException | InterruptedException e) {
			System.err.println("Could not close application window!");
			e.printStackTrace(System.err);
		}

		try {
			fftService.stop();
		} catch (IOException ioException) {
			 System.err.println("Could not stop audio device!");
			 ioException.printStackTrace(System.err);
		}
		try {
			vtsService.disconnect();
		} catch (IOException ioException) {
			System.err.println("Could not stop connection with VTS API");
			ioException.printStackTrace(System.err);
		}

		try {
			saveSettings();
		} catch (IOException ioException) {
			System.err.println("Could not save settings!");
			ioException.printStackTrace(System.err);
		}
	}

}
