package me.yuzu.vts.fft;

import me.yuzu.vts.fft.FftService.BucketType;
import me.yuzu.vts.fft.FftService.OutputType;

public class Settings {

	public volatile String connectionUrl = "ws://localhost:8001";
	public volatile String authenticationToken = "";

	public volatile String audioDevice = "";
	public volatile boolean stereo = false;
	public volatile int fftBuckets = 10;
	public volatile int frequencyStart = 25;
	public volatile int frequencyEnd = 10000;
	public volatile double volume = 1.0d;
	public volatile double noiseFloor = 0.0001d;
	public volatile OutputType outputType = OutputType.LOGARITHMIC;
	public volatile BucketType bucketType = BucketType.LINEAR;

}