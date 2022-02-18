package StardistOrion;

import java.net.URL;
import java.util.List;

import org.scijava.app.StatusService;
import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.lut.LUTService;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;

public abstract class StarDist2DBase {

    protected LogService log;
    protected CommandService command;
    protected DatasetService dataset;
    protected StatusService status;
    protected LUTService lut;

    // ---------

    protected boolean exportPointRois = false;
    protected boolean exportBboxRois = false;

    protected RoiManager roiManager = null;
    protected ImagePlus labelImage = null;
    protected int labelId = 0;
    protected long labelCount = 0;
    protected static final int MAX_LABEL_ID = 65535;

    // ---------

    protected URL getResource(final String name) {
        return StarDist2DBase.class.getClassLoader().getResource(name);
    }

    protected boolean showError(String msg) {
        //ui.showDialog(msg, MessageType.ERROR_MESSAGE);
         IJ.error(msg);
        return false;
    }

    // ---------

    protected void export(String outputType, Candidates polygons, int framePosition, long numFrames, String roiPosition) {
        switch (outputType) {
            case "ROI Manager":
            exportROIs(polygons, framePosition, numFrames, roiPosition);
            break;
        case "Label Image":
            exportLabelImage(polygons, framePosition);
            break;
        case "Both":
            exportROIs(polygons, framePosition, numFrames, roiPosition);
            exportLabelImage(polygons, framePosition);
            break;
        case "Polygons":
            exportPolygons(polygons);
            break;
        default:
            showError(String.format("Invalid %s \"%s\"", "Output Type", outputType));
        }
    }

    protected void exportROIs(Candidates polygons, int framePosition, long numFrames, String roiPosition) {
        final boolean isTimelapse = framePosition > 0;
        if (roiManager == null) {
            roiManager = RoiManager.getInstance();
            if (roiManager == null) roiManager = new RoiManager();
            roiManager.reset(); // clear all rois
        }

        for (final int i : polygons.getWinner()) {
            final PolygonRoi polyRoi = polygons.getPolygonRoi(i);
            if (isTimelapse) setRoiPosition(polyRoi, framePosition, roiPosition);
            roiManager.add(polyRoi, -1);
            if (exportPointRois) {
                final PointRoi pointRoi = polygons.getOriginRoi(i);
                if (isTimelapse) setRoiPosition(pointRoi, framePosition, roiPosition);
                roiManager.add(pointRoi, -1);
            }
            if (exportBboxRois) {
                final Roi bboxRoi = polygons.getBboxRoi(i);
                if (isTimelapse) setRoiPosition(bboxRoi, framePosition, roiPosition);
                roiManager.add(bboxRoi, -1);
            }
        }
        if (roiManager.isVisible()) roiManager.repaint();
    }
    
    protected void setRoiPosition(Roi roi, int framePosition, String roiPosition) {
        switch (roiPosition) {
        case "Stack":
            roi.setPosition(framePosition);
            break;
        case "Hyperstack":
            roi.setPosition(0, 0, framePosition);
            break;
        default:
            showError(String.format("Invalid %s \"%s\"", "ROI Position", roiPosition));
        }
    }

    protected void exportLabelImage(Candidates polygons, int framePosition) {
        if (labelImage == null)
            labelImage = createLabelImage();
        if (framePosition > 0)
            labelImage.setT(framePosition);
        final ImageProcessor ip = labelImage.getProcessor();
        final List<Integer> winner = polygons.getWinner();
        final int numWinners = winner.size();
        // winners are ordered by score -> draw from last to first to give priority to higher scores in case of overlaps
        for (int i = numWinners-1; i >= 0; i--) {
            final PolygonRoi polyRoi = polygons.getPolygonRoi(winner.get(i));
            ip.setColor(1 + ((labelId + i) % MAX_LABEL_ID));
            ip.fill(polyRoi);
        }
        labelCount += numWinners;
        labelId = (labelId + numWinners) % MAX_LABEL_ID;
    }

    abstract protected void exportPolygons(Candidates polygons);

    abstract protected ImagePlus createLabelImage();

    protected Dataset labelImageToDataset(String outputType) {
        if (outputType.equals("Label Image") || outputType.equals("Both")) {
            if (labelCount > MAX_LABEL_ID) {
                log.error(String.format("Found more than %d segments -> label image does contain some repetitive IDs.\n(\"%s\" output instead does not have this problem).", MAX_LABEL_ID, "ROI Manager"));
            }
            final boolean isTimelapse = labelImage.getNFrames() > 1;
            final Img labelImg = (Img) ImageJFunctions.wrap(labelImage);
            final AxisType[] axes = isTimelapse ? new AxisType[]{Axes.X, Axes.Y, Axes.TIME} : new AxisType[]{Axes.X, Axes.Y};
            final Dataset ds = Utils.raiToDataset(dataset, "Label Image", labelImg, axes);
            // set LUT 
            try {
                ds.initializeColorTables(1);                
                 //ds.setColorTable(lut.loadLUT(lut.findLUTs().get("StarDist.lut")), 0);
                //ds.setColorTable(lut.loadLUT(getResource("luts/StarDist.lut")), 0);
                ds.setChannelMinimum(0, 0);
                ds.setChannelMaximum(0, Math.min(labelCount, MAX_LABEL_ID));
            } catch (Exception e) {
                IJ.log("Couldn't set LUT for label image.");
                e.printStackTrace();
            }
            return ds;
        } else {
            return null;
        }        
    }
}
