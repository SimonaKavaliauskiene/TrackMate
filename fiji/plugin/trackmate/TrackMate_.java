package fiji.plugin.trackmate;

import fiji.plugin.trackmate.features.FeatureFacade;
import fiji.plugin.trackmate.gui.TrackMateFrame;
import fiji.plugin.trackmate.segmentation.LogSegmenter;
import fiji.plugin.trackmate.segmentation.SegmenterSettings;
import fiji.plugin.trackmate.segmentation.SpotSegmenter;
import fiji.plugin.trackmate.tracking.LAPTracker;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.PlugIn;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;

import javax.swing.SwingUtilities;

import mpicbg.imglib.algorithm.roi.MedianFilter;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.type.numeric.RealType;

import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;


/**
 * <p>The TrackMate_ class runs on the currently active time lapse image (2D or 3D) 
 * and both identifies and tracks bright blobs over time.</p>
 * 
 * <p><b>Required input:</b> A 2D or 3D time-lapse image with bright blobs.</p>
 *
 * @author Nicholas Perry, Jean-Yves Tinevez - Institut Pasteur - July-Aug-Sep 2010
 *
 */
public class TrackMate_ <T extends RealType<T>> implements PlugIn {
	
	public static final String PLUGIN_NAME_STR = "Track Mate";
	public static final String PLUGIN_NAME_VERSION = ".alpha";
	
	
	/** Contain the segmentation result, un-filtered.*/
	private TreeMap<Integer,List<Spot>> spots;
	/** Contain the Spot retained for tracking, after thresholding by features. */
	private TreeMap<Integer,List<Spot>> selectedSpots = new TreeMap<Integer, List<Spot>>();
	/** The tracks as a graph. */
	private SimpleGraph<Spot, DefaultEdge> trackGraph;

	private Logger logger = Logger.DEFAULT_LOGGER;
	private Settings settings = new Settings();
	private List<FeatureThreshold> thresholds = new ArrayList<FeatureThreshold>();
	private SpotSegmenter<T> segmenter;

	/*
	 * CONSTRUCTORS
	 */
	
	public TrackMate_() {
		
	}
	
	public TrackMate_(Settings settings) {
		this.settings = settings;
	}
	
	
	/*
	 * RUN METHOD
	 */
	
	/** 
	 * If the <code>arg</code> is empty or <code>null</code>, simply launch the GUI.
	 * Otherwise, parse it and execute the plugin without the GUI. 
	 */
	public void run(String arg) {
		final TrackMate_<T> instance = this;
		if (null == arg || arg.isEmpty()) {
			// Launch the GUI 
			SwingUtilities.invokeLater(new Runnable() {			
				@Override
				public void run() {
					TrackMateFrame<T> mainGui = new TrackMateFrame<T>(instance);
					mainGui.setLocationRelativeTo(null);
					mainGui.setVisible(true);
				}
			});
			return;
		}

		// If ImageJ is running, we use its toolbar to echo log messages
		if (IJ.getInstance() != null)
			logger = new Logger() {
				public void log(String message, Color color) {IJ.showStatus(message);}
				public void error(String message) { IJ.error("Spot_Tracker", message);}
				public void setProgress(float val) {IJ.showProgress(val); }
				public void setStatus(String status) {IJ.showStatus(status);}
		};
		
		// Run plugin on current image
		ImagePlus imp = WindowManager.getCurrentImage();
		settings.imp = imp;
		
		// Segment
		execSegmentation();
		// Threshold
		execThresholding();
	}
	
	/*
	 * PUBLIC METHODS
	 */
	
	/**
	 * Execute the tracking part.
	 * <p>
	 * This method links all the selected spots from the thresholding part using the selected tracking algorithm.
	 * Spots are embedded in a {@link TrackNode} from which tracks info can be retrieved.
	 * Tracks can be accessed when the tracking is over using the {@link #getTracks()} method.
	 */
	public void execTracking() {
		logger.log("Starting tracking.\n", Logger.BLUE_COLOR);
		LAPTracker tracker = new LAPTracker(selectedSpots);
		tracker.setLogger(logger);
		long start = System.currentTimeMillis();
		if (tracker.checkInput() && tracker.process()) {
			long end = System.currentTimeMillis();
			logger.log(String.format("Tracking done in %.1f s.\n", (end-start)/1e3f), Logger.BLUE_COLOR);
			trackGraph = tracker.getTrackGraph();
		}
		else {
			logger.error("Problem occured in tracking:\n"+tracker.getErrorMessage());
		}
	}
	
	/**
	 * Execute the thresholding part.
	 * <p>
	 * This method simply takes all the segmented spots, and store in the field {@link #selectedSpots}
	 * the spots whose features satisfy all of the thresholds entered with the method {@link #addThreshold(FeatureThreshold)}
	 * @see TrackMate_#getSelectedSpots()
	 */
	public void execThresholding() {
		selectedSpots = thresholdSpots(spots, thresholds);
		
	}
	
	/** 
	 * Execute the segmentation part.
	 * <p>
	 * This method looks for bright blobs: bright object of approximately spherical shape, whose expected 
	 * diameter is given in argument. The following steps are applied:
	 * 
	 * <ol>
	 * 
	 * <li>An optional median filter is applied to reduce salt-and-pepper noise in hopes
	 * of false-positive reduction (the user specifies whether to run a median filter). See {@link MedianFilter}.</li>
	 *
	 * <li>The image is down-sampled in order to: [a] reduce the processing time of the other pre-processing
	 * techniques; and [b] reduce the scale of the bright objects to be a uniform diameter in
	 * all dimensions. Thus, each dimension is scaled separately to reduce the diameter in that dimension
	 * to the uniform diameter, if possible, since it is possible the original diameter
	 * is less than the desired diameter to start, in which case no down-sampling is performed in that dimension.</li>
	 * 
	 * <li>The newly down-sized, and optionally median filtered, image is then convolved with a Gaussian kernel,
	 * with <code>sigma = expected diameter / sqrt(ndim)</code>. Using this <code>sigma</code>, the Gaussian kernel is about the size of our objects (nuclei)
	 * in volume, and therefore will (hopefully) make the center of the nuclei the brightest pixel in the convolved 
	 * image, while at the same time further eliminating noise. The convolution itself is performed using the Fourier Transform
	 * approach to convolution, which resulted in a faster computation.</li>
	 * 
	 * <li>The image is then convoluted with a modified Laplacian kernel (again, via a Fourier Transform). The kernel is modified
	 * such that the center matrix cell is actually positive, and the surrounding cells are negative. The result of this
	 * modification is an image where the brightest regions in the input image are also the brightest regions in the output image.
	 * The purpose of this convolution is to accentuate the areas on the image where the bright pixels are "flowing," which should
	 * represent object centers following the Gaussian convolution.</li>
	 * 
	 * <li>Finally, the entire, newly processed image is searched for regional maxima, which are defined as pixels (or groups of equally-valued
	 * intensity pixels) that are strictly brighter than their adjacent neighbors. Following the pre-processing steps above,
	 * these regional maxima likely represent the center of the objects we would like to find and track.
	 * See {@link LogSegmenter}.
	 * </li>
	 * 
	 * <li> This gives us a collection of spots, which at this stage simply wrap a physical center location. Features are then
	 * calculated for each spot, using their location, the raw image, and the filtered image. See the {@link FeatureFacade} class
	 * for details. 
	 * </ol>
	 * 
	 * Because of the presence of noise, it is possible that some of the regional maxima found in the previous step have
	 * identified noise, rather than objects of interest. A thresholding operation based on the calculated features in this 
	 * step should allow to rule them out.
	 */
	public void execSegmentation() {
		final ImagePlus imp = settings.imp;
		if (null == imp) {
			logger.error("No image to operate on.");
			return;
		}

		int numFrames = settings.tend - settings.tstart + 1;

		/* 0 -- Initialize local variables */
		final float[] calibration = new float[] {(float) imp.getCalibration().pixelWidth, (float) imp.getCalibration().pixelHeight, (float) imp.getCalibration().pixelDepth};

		segmenter = SegmenterSettings.getSpotSegmenter(settings.segmenterSettings);
		segmenter.setCalibration(calibration);
		
		// Since we can't get the NumericType out of imp, we assume it is a FloatType.
		spots = new TreeMap<Integer, List<Spot>>();
		List<Spot> spotsThisFrame;
		
		// For each frame...
		int spotFound = 0;
		for (int i = settings.tstart-1; i < settings.tend; i++) {
			
			/* 1 - Prepare stack for use with Imglib. */
			Image<T> img = Utils.getSingleFrameAsImage(imp, i, settings); // will be cropped according to settings
			
			/* 2 Segment it */

			logger.setStatus("Frame "+(i+1)+": Segmenting...");
			logger.setProgress((2*(i-settings.tstart)) / (2f * numFrames + 1));
			segmenter.setImage(img);
			if (segmenter.checkInput() && segmenter.process()) {
				spotsThisFrame = segmenter.getResult(settings);
				for (Spot spot : spotsThisFrame)
					spot.putFeature(Feature.POSITION_T, i);
				spots.put(i, spotsThisFrame);
				logger.setStatus("Frame "+(i+1)+": found "+spotsThisFrame.size()+" spots.");
				spotFound += spotsThisFrame.size();
			} else {
				logger.error(segmenter.getErrorMessage());
				return;
			}
						
		} // Finished looping over frames
		logger.log("Found "+spotFound+" spots.\n");
		logger.setProgress(1);
		logger.setStatus("");
		return;
	}
	
	/*
	 * GETTERS / SETTERS
	 */
	
	public void setSegmenter(SpotSegmenter<T> segmenter) {
		this.segmenter = segmenter;
	}
	

	public SimpleGraph<Spot,DefaultEdge> getTrackGraph() {
		return trackGraph;
	}
	
	/**
	 * Return the spots generated by the segmentation part of this plugin. The collection are un-filtered and contain
	 * all spots. They are returned as a List of Collection, one item in the list per time-point, in order.
	 * @see #execSegmentation(ImagePlus, Settings)
	 */
	public TreeMap<Integer, List<Spot>> getSpots() {
		return spots;
	}

	/**
	 * Return the spots filtered by feature, after the execution of {@link #execThresholding(List, ArrayList, ArrayList, ArrayList)}.
	 * @see #execSegmentation(ImagePlus, Settings)
	 * @see #addThreshold(Feature, float, boolean)
	 */
	public TreeMap<Integer, List<Spot>> getSelectedSpots() {
		return selectedSpots;
	}
	
	/**
	 * Add a threshold to the list of thresholds to deal with when executing {@link #execThresholding(List, ArrayList, ArrayList, ArrayList)}.
	 */
	public void addThreshold(final FeatureThreshold threshold) { thresholds .add(threshold); }
	public void removeThreshold(final FeatureThreshold threshold) { thresholds .remove(threshold); }
	public void clearTresholds() { thresholds.clear(); }
	public List<FeatureThreshold> getFeatureThresholds() { return thresholds; }
	public void setFeatureThresholds(List<FeatureThreshold> thresholds) { this.thresholds = thresholds; }
	
	/**
	 * Set the logger that will receive the messages from the processes occuring within this plugin.
	 */
	public void setLogger(Logger logger) {
		this.logger = logger;
	}
	
	/**
	 * Return the {@link Settings} object that determines the behavior of this plugin.
	 */
	public Settings getSettings() {
		return settings;
	}
	
	public void setSettings(Settings settings) {
		this.settings = settings;
	}
	
	
	/*
	 * STATIC METHODS
	 */
	
	/**
	 * Convenience static method that executes the thresholding part.
	 * <p>
	 * Given a list of spots, only spots with the feature satisfying <b>all</b> of the thresholds given
	 * in argument are returned. 
	 */
	public static TreeMap<Integer, List<Spot>> thresholdSpots(final TreeMap<Integer, List<Spot>> spots, final List<FeatureThreshold> featureThresholds) {
		TreeMap<Integer, List<Spot>> selectedSpots = new TreeMap<Integer, List<Spot>>();
		Collection<Spot> spotThisFrame, spotToRemove;
		List<Spot> spotToKeep;
		Float val, tval;	
		
		for (int timepoint : spots.keySet()) {
			
			spotThisFrame = spots.get(timepoint);
			spotToKeep = new ArrayList<Spot>(spotThisFrame);
			spotToRemove = new ArrayList<Spot>(spotThisFrame.size());

			for (FeatureThreshold threshold : featureThresholds) {

				tval = threshold.value;
				if (null == tval)
					continue;
				spotToRemove.clear();

				if (threshold.isAbove) {
					for (Spot spot : spotToKeep) {
						val = spot.getFeature(threshold.feature);
						if (null == val)
							continue;
						if ( val < tval)
							spotToRemove.add(spot);
					}

				} else {
					for (Spot spot : spotToKeep) {
						val = spot.getFeature(threshold.feature);
						if (null == val)
							continue;
						if ( val > tval)
							spotToRemove.add(spot);
					}
				}
				spotToKeep.removeAll(spotToRemove); // no need to treat them multiple times
			}
			selectedSpots.put(timepoint, spotToKeep);
		}
		return selectedSpots;
	}

	
	
}