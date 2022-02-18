package StardistOrion;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.stream.IntStream;
import org.scijava.command.Command;
import ij.IJ;
import ij.ImagePlus;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.scijava.ItemIO;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, label = "PML - StarDist 2D NMS")
public class StarDist2DNMS extends StarDist2DBase implements Command {

    @Parameter(label="Probability/Score Image")
    private Dataset prob;
    @Parameter(label="Distance Image")
    private Dataset dist;
    @Parameter(label="Label Image", type=ItemIO.OUTPUT)
    private Dataset label;
    @Parameter(label="polygons",type=ItemIO.OUTPUT)
    private Candidates polygons;
    @Parameter(label="probThresh", stepSize="0.05", min="0", max="1")
    private double probThresh = 0.5;
    @Parameter(label="nmsThresh", stepSize="0.05", min="0", max="1")
    private double nmsThresh = 0.4;
    @Parameter(label="outputType", choices={"ROI Manager", "Label Image", "Both"})
    private String outputType = "ROI Manager";
     // ---------
    @Parameter(label="excludeBoundary", min="0", stepSize="1")
    private int excludeBoundary = 2;
    @Parameter(label="roiPosition", choices={"Stack", "Hyperstack"})
    private String roiPosition = "Automatic";
   @Parameter(label="verbose")
    private boolean verbose = false;
   
   private String probImage = "Probability/Score Image";
   private String distImage = "Distance Image";
    // ---------

    @Override
    public void run() {
        if (!checkInputs()) return;

        final RandomAccessibleInterval<FloatType> probRAI = (RandomAccessibleInterval<FloatType>) prob.getImgPlus();
        final RandomAccessibleInterval<FloatType> distRAI = (RandomAccessibleInterval<FloatType>) dist.getImgPlus();

        final LinkedHashSet<AxisType> probAxes = Utils.orderedAxesSet(prob);
        final LinkedHashSet<AxisType> distAxes = Utils.orderedAxesSet(dist);
        final boolean isTimelapse = probAxes.contains(Axes.TIME);

        if (isTimelapse) {
            final int probTimeDim = IntStream.range(0, probAxes.size()).filter(d -> prob.axis(d).type() == Axes.TIME).findFirst().getAsInt();
            final int distTimeDim = IntStream.range(0, distAxes.size()).filter(d -> dist.axis(d).type() == Axes.TIME).findFirst().getAsInt();
            final long numFrames = prob.getFrames();

            for (int t = 0; t < numFrames; t++) {
                final Candidates polygons = new Candidates(Views.hyperSlice(probRAI, probTimeDim, t), Views.hyperSlice(distRAI, distTimeDim, t), probThresh, excludeBoundary, verbose ? log : null);
                polygons.nms(nmsThresh);
                if (verbose)
                    log.info(String.format("frame %03d: %d polygon candidates, %d remain after non-maximum suppression", t, polygons.getSorted().size(), polygons.getWinner().size()));
                export(outputType, polygons, 1+t, numFrames, roiPosition);
            }
        } else {
            final Candidates polygons = new Candidates(probRAI, distRAI, probThresh, excludeBoundary, verbose ? log : null);
            polygons.nms(nmsThresh);
            if (verbose)
                log.info(String.format("%d polygon candidates, %d remain after non-maximum suppression", polygons.getSorted().size(), polygons.getWinner().size()));
            export(outputType, polygons, 0, 0, roiPosition);
        }

        label = labelImageToDataset(outputType);

        // call at the end of the run() method
        //CommandFromMacro.record(this, this.command);
    }


    private boolean checkInputs() {
        final LinkedHashSet<AxisType> probAxes = Utils.orderedAxesSet(prob);
        final LinkedHashSet<AxisType> distAxes = Utils.orderedAxesSet(dist);

        if (!( (prob.numDimensions() == 2 && probAxes.containsAll(Arrays.asList(Axes.X, Axes.Y))) ||
               (prob.numDimensions() == 3 && probAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.TIME))) ))
            return showError(String.format("%s must be a 2D image or timelapse.", probImage));

        if (!( (dist.numDimensions() == 3 && distAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.CHANNEL))            && dist.getChannels() >= 3) ||
               (dist.numDimensions() == 4 && distAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.CHANNEL, Axes.TIME)) && dist.getChannels() >= 3) ))
            return showError(String.format("%s must be a 2D image or timelapse with at least three channels.", distImage));

        if ((prob.numDimensions() + 1) != dist.numDimensions())
            return showError(String.format("Axes of %s and %s not compatible.", probImage, distImage));

        if (prob.getWidth() != dist.getWidth() || prob.getHeight() != dist.getHeight())
            return showError(String.format("Width or height of %s and %s differ.", probImage, distImage));

        if (prob.getFrames() != dist.getFrames())
            return showError(String.format("Number of frames of %s and %s differ.", probImage, distImage));

        final AxisType[] probAxesArray = probAxes.stream().toArray(AxisType[]::new);
        final AxisType[] distAxesArray = distAxes.stream().toArray(AxisType[]::new);
        if (!( probAxesArray[0] == Axes.X && probAxesArray[1] == Axes.Y ))
            return showError(String.format("First two axes of %s must be a X and Y.", probImage));
        if (!( distAxesArray[0] == Axes.X && distAxesArray[1] == Axes.Y ))
            return showError(String.format("First two axes of %s must be a X and Y.", distImage));

        if (!(0 <= nmsThresh && nmsThresh <= 1))
            return showError(String.format("%s must be between 0 and 1.", "Overlap Threshold"));

        if (excludeBoundary < 0)
            return showError(String.format("%s must be >= 0", "Exclude Boundary"));

        if (!(outputType.equals("ROI Manager") || outputType.equals("Label Image") || outputType.equals("Both") || outputType.equals("Polygons")))
            return showError(String.format("%s must be one of {\"%s\", \"%s\", \"%s\"}.", "Output Type", "ROI Manager", "Label Image", "Both"));

        if (outputType.equals("Polygons") && probAxes.contains(Axes.TIME))
            return showError(String.format("Timelapse not supported for output type \"%s\"", "Polygons"));

        if (!(roiPosition.equals("Stack") || roiPosition.equals("Hyperstack")))
            return showError(String.format("%s must be one of {\"%s\", \"%s\"}.", "Roi position", "Stack", "Hyperstack"));        
        
        return true;
    }


    @Override
    protected void exportPolygons(Candidates polygons) {
        this.polygons = polygons;
    }

    @Override
    protected ImagePlus createLabelImage() {
        return IJ.createImage("Label Image", "16-bit black", (int)prob.getWidth(), (int)prob.getHeight(), 1, 1, (int)prob.getFrames());
    }


    public static void main(final String... args) throws Exception {
        final ImageJ ij = new ImageJ();
        ij.launch(args);

        Dataset prob = ij.scifio().datasetIO().open(StarDist2DNMS.class.getClassLoader().getResource("blobs_prob.tif").getFile());
        Dataset dist = ij.scifio().datasetIO().open(StarDist2DNMS.class.getClassLoader().getResource("blobs_dist.tif").getFile());

        ij.ui().show(prob);
        ij.ui().show(dist);

        final HashMap<String, Object> params = new HashMap<>();
        params.put("prob", prob);
        params.put("dist", dist);
        ij.command().run(StarDist2DNMS.class, true, params);

        IJ.run("Tile");
    }

}
