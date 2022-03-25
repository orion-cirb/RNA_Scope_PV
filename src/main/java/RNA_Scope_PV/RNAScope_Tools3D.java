package RNA_Scope_PV;

import RNA_Scope_PV_StardistOrion.StarDist2D;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import static ij.IJ.setBackgroundColor;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.plugin.RGBStackMerge;
import ij.plugin.ZProjector;
import ij.plugin.filter.RankFilters;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.ImageIcon;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.plugins.util.ImageProcessorReader;
import mcib3d.geom.Object3D;
import mcib3d.geom.Object3DVoxels;
import mcib3d.geom.Object3D_IJUtils;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.geom.Point3D;
import mcib3d.image3d.ImageHandler;
import mcib3d.image3d.ImageInt;
import mcib3d.image3d.ImageLabeller;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.ArrayUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;




/**
 *
 * @author phm
 */

public class RNAScope_Tools3D {
    

    public double minCellVol= 150;
    public double maxCellVol = 15000;
    public Calibration cal = new Calibration();
    public final ImageIcon icon = new ImageIcon(this.getClass().getResource("/Orion_icon.png"));
    public boolean pnn = false;
    public String pnnDetection = "";
    public boolean obj63 = false;
    
    // Ridge detection
    public double ridgeLine = 15;
    public double ridgeHigh = 120;
    public double ridgeLow = 8;    
    private final ridge.Lines_ ridgeDectection = new ridge.Lines_();
    
    // Stardist
    public boolean stardist = false;
    public Object syncObject = new Object();
    public final double stardistPercentileBottom = 0.2;
    public final double stardistPercentileTop = 99.8;
    public final double stardistProbThreshNuc = 0.55;
    public final double stardistOverlayThreshNuc = 0.35;
    public String stardistOutput = "Label Image"; 
    protected String starDistModel = null;
    public File modelsPath = new File(IJ.getDirectory("imagej")+File.separator+"models");
    
    // CLIJ2
    public CLIJ2 clij2 = CLIJ2.getInstance();
    
    
    /**
     * check  installed modules
     * @return 
     */
    public boolean checkInstalledModules() {
        // check install
        ClassLoader loader = IJ.getClassLoader();
        try {
            loader.loadClass("net.haesleinhuepf.clij2.CLIJ2");
        } catch (ClassNotFoundException e) {
            IJ.log("CLIJ not installed, please install from update site");
            return false;
        }
        try {
            loader.loadClass("mcib3d.geom.Object3D");
        } catch (ClassNotFoundException e) {
            IJ.log("3D ImageJ Suite not installed, please install from update site");
            return false;
        }
        return true;
    }

    /**
     *
     * @param img
     */
    public void closeImages(ImagePlus img) {
        img.flush();
        img.close();
    }
    
    
    
    
  /**
     * return objects population in an binary image
     * @param img
     * @return pop objects population
     */

    public  Objects3DPopulation getPopFromImage(ImagePlus img) {
        // label binary images first
        ImageLabeller labeller = new ImageLabeller();
        ImageInt labels = labeller.getLabels(ImageHandler.wrap(img));
        Objects3DPopulation pop = new Objects3DPopulation(labels);
        return pop;
    }
    
    
     /**  
     * median 3D box filter
     * Using CLIJ2
     * @param imgCL
     * @param sizeX
     * @param sizeY
     * @param sizeZ
     * @return imgOut
     */ 
    public ClearCLBuffer medianFilter(ClearCLBuffer imgCL, double sizeX, double sizeY, double sizeZ) {
        ClearCLBuffer imgIn = clij2.push(imgCL);
        ClearCLBuffer imgOut = clij2.create(imgIn);
        clij2.median3DBox(imgIn, imgOut, sizeX, sizeY, sizeZ);
        clij2.release(imgCL);
        return(imgOut);
    }
    
    /*Median filter 
     * 
     * @param img
     * @param size
     */ 
    public void median_filter(ImagePlus img, double size) {
        RankFilters median = new RankFilters();
        for (int s = 1; s <= img.getNSlices(); s++) {
            img.setZ(s);
            median.rank(img.getProcessor(), size, RankFilters.MEDIAN);
            img.updateAndDraw();
        }
    }
    
    /*
    Find starDist models in Fiji models folder
    */
    private String[] findStardistModels() {
        FilenameFilter filter = (dir, name) -> name.endsWith(".zip");
        File[] modelList = modelsPath.listFiles(filter);
        String[] models = new String[modelList.length];
        for (int i = 0; i < modelList.length; i++) {
            models[i] = modelList[i].getName();
        }
        Arrays.sort(models);
        return(models);
    }
    
    
    /**
     * Find mean Intensity of low PV cells
     * return % of background
     */
    private double findLowPVIntensity(Objects3DPopulation popPV, double bgInt, ArrayList<Point3D> pvPoints, ImagePlus img) {
        double intensity = 0;
        int nbCell = 0;
        ImageHandler imh = ImageHandler.wrap(img);
        for (int i = 0; i < popPV.getNbObjects(); i++) {
            Object3D obj = popPV.getObject(i);
            // If point inside cell read mean intensity
            for (Point3D p : pvPoints) {
                if (obj.inside(p)) {
                    nbCell++;
                    intensity += obj.getPixMeanValue(imh);
                }
            }
        }
        double bgPourcent = bgInt / (intensity/nbCell);
        System.out.println("PV low = "+ bgPourcent + "of backgroud");
        return(bgPourcent);    
    }
    
        
    /**
     * Filters cells on sphericity
     */
    public void filterCells(Objects3DPopulation popPV, double sphCoef) {
        for (int i = 0; i < popPV.getNbObjects(); i++) {
            Object3D obj = popPV.getObject(i);
            double sph = obj.getSphericity(true);
            if (sph < sphCoef){
                popPV.removeObject(i);
                i--;
            }
        }
    }
    
    /**
     * Ask for parameters
     * @param channels
     * @return 
     */
    
    public String dialog() {
        String[] models = findStardistModels();
        GenericDialogPlus gd = new GenericDialogPlus("IHC parameters");
        gd.setInsets(0, 10, 0);
        gd.addImage(icon);
        gd.addDirectoryField("Image folder : ", "");
        gd.addMessage("Cells detection",Font.getFont("Monospace"), Color.blue);
        gd.addCheckbox("StarDist :", stardist);
        if (models.length == 0)
            gd.addFileField("Model file :", starDistModel);
        gd.showDialog();
        String imageFolder = gd.getNextString();
        stardist = gd.getNextBoolean();
        if (models.length == 0)
            starDistModel = gd.getNextString();
        else {
            for (String model : models) {
                if (model.equals("dsb2018_heavy_augment.zip"))
                    starDistModel = modelsPath+File.separator+model;
            }
        }
        return(imageFolder);
    }
  
    /**
     * Ask for parameters
     * @param channels
     * @return 
     */
    
    public int[] dialog(String[] channels, List<String> channelsName) {
        String[] models = findStardistModels();
        String[] pnnDetections = {"Ridge","Outliner Cell"};
        GenericDialogPlus gd = new GenericDialogPlus("IHC parameters");
        gd.setInsets(0, 10, 0);
        gd.addImage(icon);
        gd.addMessage("Choose channels",Font.getFont("Monospace"), Color.blue);
        int index = 0;
        for (String chName : channelsName) {
            gd.addChoice(chName, channels, channels[index]);
            index++;
        }
        if (pnn) {
            gd.addMessage("PNN cells detection",Font.getFont("Monospace"), Color.blue);
            gd.addChoice("PNN method : ", pnnDetections, pnnDetections[0]);
        }
        gd.addMessage("Cells detection method",Font.getFont("Monospace"), Color.blue);
        gd.addCheckbox("StarDist ", stardist);
        if (models.length == 0)
            gd.addFileField("Model file :", starDistModel);
        gd.addCheckbox("Objective X63", obj63);
        gd.showDialog();
        int[] chChoices = new int[channelsName.size()];
        for (int n = 0; n < chChoices.length; n++) {
            chChoices[n] = ArrayUtils.indexOf(channels, gd.getNextChoice());
        }
        if (pnn) {
            pnnDetection = gd.getNextChoice();
        }
        stardist = gd.getNextBoolean();
        if (models.length == 0)
            starDistModel = gd.getNextString();
        else {
            for (String model : models) {
                if (model.equals("dsb2018_heavy_augment.zip"))
                    starDistModel = modelsPath+File.separator+model;
            }
        }
        obj63 = gd.getNextBoolean();
        if (gd.wasCanceled())
            chChoices = null;
        return(chChoices);
    }
    
    
    /**
     * Find images in folder
     */
    public ArrayList findImages(String imagesFolder, String imageExt) {
        File inDir = new File(imagesFolder);
        String[] files = inDir.list();
        ArrayList<String> images = new ArrayList();
        if (files == null) {
            System.out.println("No Image found in "+imagesFolder);
            return null;
        }
        else {
            
            for (String f : files) {
                // Find images with extension
                String fileExt = FilenameUtils.getExtension(f);
                if (fileExt.equals(imageExt))
                    images.add(imagesFolder + File.separator + f);
            }
            Collections.sort(images);
            if (!images.isEmpty())
                return(images);
            else
                return(null);
            
        }
    }
    
     /**
     * Find channels name
     * @param imageName
     * @return 
     * @throws loci.common.services.DependencyException
     * @throws loci.common.services.ServiceException
     * @throws loci.formats.FormatException
     * @throws java.io.IOException
     */
    public String[] findChannels (String imageName, IMetadata meta, ImageProcessorReader reader) throws DependencyException, ServiceException, FormatException, IOException {
        int chs = reader.getSizeC();
        String[] channels = new String[chs];
        String imageExt =  FilenameUtils.getExtension(imageName);
        switch (imageExt) {
            case "nd" :
                for (int n = 0; n < chs; n++) 
                {
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n).toString();
                }
                break;
            case "lif" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n).toString();
                break;
            case "czi" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelFluor(0, n).toString();
                break;
            case "ics" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelExcitationWavelength(0, n).value().toString();
                break;    
            default :
                for (int n = 0; n < chs; n++)
                    channels[0] = Integer.toString(n);
        }
        return(channels);         
    }
    
     /**
     * Find image calibration
     * @param meta
     * @return 
     */
    public Calibration findImageCalib(IMetadata meta) {
        cal = new Calibration();  
        // read image calibration
        cal.pixelWidth = meta.getPixelsPhysicalSizeX(0).value().doubleValue();
        cal.pixelHeight = cal.pixelWidth;
        if (meta.getPixelsPhysicalSizeZ(0) != null)
            cal.pixelDepth = meta.getPixelsPhysicalSizeZ(0).value().doubleValue();
        else
            cal.pixelDepth = 1;
        cal.setUnit("microns");
        System.out.println("x cal = " +cal.pixelWidth+", z cal=" + cal.pixelDepth);
        return(cal);
    }
    
    
    
    /**
     * Cells segmentation
     * @param imgCells
     * @param roi
     * @param blur1
     * @param blur2
     * @param med
     * @param th
     * @param removeOutliers
     * @param minCellVol
     * @param maxCellVol
     * @return 
     */
    public Objects3DPopulation findCells(ImagePlus imgCells, Roi roi, int blur1, int blur2, double med, String th, boolean removeOutliers, int rad, 
            int std) {
        ImagePlus img = new Duplicator().run(imgCells);
        img.setCalibration(imgCells.getCalibration());
        if (removeOutliers)
            IJ.run(imgCells, "Remove Outliers", "block_radius_x="+rad+" block_radius_y="+rad+" standard_deviations="+std+" stack");
        median_filter(img, med);
        ImageStack stack = new ImageStack(img.getWidth(), img.getHeight());
        for (int i = 1; i <= img.getStackSize(); i++) {
            img.setZ(i);
            img.updateAndDraw();
            IJ.run(img, "Nuclei Outline", "blur="+blur1+" blur2="+blur2+" threshold_method="+th+" outlier_radius=0 outlier_threshold=1 max_nucleus_size=500 "
                    + "min_nucleus_size=20 erosion=5 expansion_inner=5 expansion=5 results_overlay");
            img.setZ(1);
            img.updateAndDraw();
            ImagePlus mask = new ImagePlus("mask", img.createRoiMask().getBufferedImage());
            ImageProcessor ip =  mask.getProcessor();
            ip.invertLut();
//            for (int n = 0; n < 3; n++) {
//                ip.erode();
//                ip.dilate();
//            }
            stack.addSlice(ip);
        }
        ImagePlus imgStack = new ImagePlus("Nucleus", stack);
        imgStack.setCalibration(imgCells.getCalibration());
        if (roi != null) {
            imgStack.setRoi(roi);
            IJ.run("Colors...", "foreground=white background=black selection=yellow");
            IJ.run(imgStack, "Clear Outside","stack");
            imgStack.deleteRoi();
        }
        Objects3DPopulation cellPop = new Objects3DPopulation(getPopFromImage(imgStack).getObjectsWithinVolume​(minCellVol, maxCellVol, true));
       
        cellPop.removeObjectsTouchingBorders(imgStack, false);
        closeImages(imgStack);
        closeImages(img);
        return(cellPop);
    }
   
 /**
     * Cells segmentation2
     * @param imgCells
     * @param blur1
     * @param blur2
     * @param th
     * @return 
     */
    public Objects3DPopulation findCellsPiriform(ImagePlus imgCells, Roi roi, int blur1, int blur2, double med, String th) {
        ImagePlus img = new Duplicator().run(imgCells);
        img.setCalibration(imgCells.getCalibration());
        median_filter(img, med);
        IJ.run(img, "Difference of Gaussians", "  sigma1="+blur1+" sigma2="+blur2+" enhance stack");
        img.setSlice(img.getNSlices()/2);
        IJ.setAutoThreshold(img, th + " dark");
        Prefs.blackBackground = false;
        IJ.run(img, "Convert to Mask","method="+th+" background=Dark");
        if (roi != null) {
            roi.setLocation(0, 0);
            img.setRoi(roi);
            IJ.run("Colors...", "foreground=black background=white selection=yellow");
            IJ.run(img, "Clear Outside","stack");
            img.deleteRoi();
            for (int n = 0; n < 3; n++) {
                img.getProcessor().erode();
                img.getProcessor().dilate();
            }
        }

        Objects3DPopulation cellPop = new Objects3DPopulation(getPopFromImage(img).getObjectsWithinVolume​(minCellVol-100, maxCellVol, true));
        cellPop.removeObjectsTouchingBorders(img, false);
        closeImages(img);
        return(cellPop);
    }
    
    
     /**
     * Find dots population with Stardist
     */
    public Objects3DPopulation stardistCellsPop(ImagePlus imgCells) throws IOException{
        ImagePlus img = new Duplicator().run(imgCells);
        ClearCLBuffer imgCL = clij2.push(img);
        ClearCLBuffer imgCLMed = medianFilter(imgCL, 2, 2, 2);
        clij2.release(imgCL);
        ImagePlus imgCellsMed = clij2.pull(imgCLMed);
        clij2.release(imgCLMed);
        // Go StarDist
        File starDistModelFile = new File(starDistModel);
        StarDist2D star = new StarDist2D(syncObject, starDistModelFile);
        star.loadInput(imgCellsMed); 
        star.setParams(stardistPercentileBottom, stardistPercentileTop, stardistProbThreshNuc, stardistOverlayThreshNuc, stardistOutput);
        star.run();
        // label in 3D
        ImagePlus nuclei = star.associateLabels();
        nuclei.setCalibration(cal);
        ImageInt label3D = ImageInt.wrap(nuclei);
        label3D.setCalibration(cal);
        Objects3DPopulation nucPop = new Objects3DPopulation(label3D);
        Objects3DPopulation nPop = new Objects3DPopulation(nucPop.getObjectsWithinVolume(minCellVol, maxCellVol, true));
        closeImages(nuclei);
        closeImages(imgCellsMed);
        return(nPop);
    } 
    
   /**
     * Find PNN cells with cellOutliner
     * @param pts
     * @param imgPNN
     * @return PNNCellsPop
     */
    public Objects3DPopulation findPNNCellsOutliner(ImagePlus imgPNN, Roi roi, ArrayList<Point3D> pts) {
        ImagePlus img = imgPNN.duplicate();
        img.setTitle("PNN Cells");
        IJ.run(img, "Median...", "radius=4 stack");
        Objects3DPopulation cellPop = new Objects3DPopulation();
        // add points 

        for (int i = 0; i < pts.size(); i++) {
            Point3D point = pts.get(i);
            img.setPosition(i+1);
            img.setRoi(new PointRoi(point.x, point.y));
            img.updateAndDraw();
            IJ.run(img, "Cell Outliner", "cell_radius=50 tolerance=0.74 kernel_width=13 kernel_smoothing=1 polygon_smoothing=2 weighting_gamma=3 iterations=3 dilate=5 all_slices");
            ImagePlus imgCells = WindowManager.getImage("PNN Cells Cell Outline");
            imgCells.hide();
            imgCells.setCalibration(img.getCalibration());
            IJ.run(imgCells, "Select None", "");
            cellPop.addObject(new Object3DVoxels(ImageHandler.wrap(imgCells)));
            cellPop.removeObjectsTouchingBorders(imgCells, false);
            closeImages(imgCells);
        }
        closeImages(img);
        return(cellPop);
    }
    
   /**
     * PNN Cells segmentation with Ridge
     * @param imgCells
     * @param roi
     * @param pts
     * @return 
     */
    public Objects3DPopulation findPNNCellsRidge(ImagePlus imgCells, Roi roi, ArrayList<Point3D> pts) { 
        ridgeDectection.lineWidth = ridgeLine;
        ridgeDectection.sigma = 2.8;
        ridgeDectection.lowerThresh = 0;
        ridgeDectection.upperThresh = 0.85;
        ridgeDectection.contrastHigh = ridgeHigh;
        ridgeDectection.contrastLow = ridgeLow;
        ridgeDectection.doStack = false;
        Objects3DPopulation cellPop = new Objects3DPopulation();
        for (int i = 0; i < pts.size(); i++) {
            Point3D pt = pts.get(i);
            Rectangle cellRoi = new Rectangle(pt.getRoundX() - 500/2, pt.getRoundY() - 300/2, 500, 300);
            // find cell contours
            if (roi.contains(pt.getRoundX(), pt.getRoundY())) {
                int zStart =  (pt.getRoundZ() - 4 < 1) ? 1 : pt.getRoundZ() - 4;
                int zStop = (pt.getRoundZ() + 4 > imgCells.getNSlices()) ? imgCells.getNSlices() : pt.getRoundZ() + 4;
                ImageStack imgStackBin = new ImageStack(imgCells.getWidth(), imgCells.getHeight());
                int z = 0;
                for (int n = zStart; n <= zStop; n++) {
                    ImagePlus img = new Duplicator().run(imgCells, n, n);
                    IJ.run(img, "8-bit","");
                    img.setRoi(cellRoi);
                    setBackgroundColor(255, 255, 255);
                    IJ.run(img, "Clear Outside", "stack");
                    img.deleteRoi();
                    ridgeDectection.setup("", img);
                    ridgeDectection.run(img.getProcessor());
                    ImagePlus imgRidge = ridgeDectection.makeBinary();
                    imgStackBin.addSlice("", imgRidge.getProcessor(), z);
                    closeImages(img);
                    z++;
                }
                ImagePlus imgBin = new ImagePlus("", imgStackBin);
                imgBin.setCalibration(cal);
                Object3DVoxels cellObj = Object3D_IJUtils.createObject3DVoxels(imgBin, 255);
                closeImages(imgBin);
                cellObj.setNewCenter(cellObj.getCenterX(), cellObj.getCenterY(), pt.getRoundZ());
                cellPop.addObject(cellObj);
            }                
        }
        return(cellPop);
    }
    
    
    
    public Object3D findAssociatedCell(Objects3DPopulation pop, Object3D cellObj) {
        Object3D objAsso = null;
        for (int i = 0; i < pop.getNbObjects(); i ++) {
            Object3D obj = pop.getObject(i);
            if (cellObj.hasOneVoxelColoc(obj)) {
                objAsso = obj;
                return(objAsso);
            }
        }
        return(objAsso);
    }
    
    public void saveRNAObjects(Objects3DPopulation cellsPop,ImagePlus imgCells, String name) {
        // green Objects, gray image
        ImageHandler imgObjs = ImageHandler.wrap(imgCells).createSameDimensions();
        // draw obj population
        cellsPop.draw(imgObjs, 255);
        labelsObject(cellsPop, imgObjs.getImagePlus());
        ImagePlus[] imgColors = {null, imgObjs.getImagePlus(), null, imgCells};
        ImagePlus imgObjects = new RGBStackMerge().mergeHyperstacks(imgColors, false);
        imgObjects.setCalibration(imgCells.getCalibration());
        FileSaver ImgObjectsFile = new FileSaver(imgObjects);
        ImgObjectsFile.saveAsTiff(name); 
        imgObjs.closeImagePlus();
    }
    
    public void saveIHCObjects(Objects3DPopulation Pop1, Objects3DPopulation Pop2, Objects3DPopulation Pop3, ImagePlus imgCells, String name) {
       // Pop1 blue Pop2 red Pop3 green
        ImageHandler ImgObj1 = ImageHandler.wrap(imgCells).createSameDimensions();
        ImageHandler ImgObj2 = ImgObj1.duplicate();
        ImageHandler ImgObj3 = ImgObj2.duplicate();
        // draw obj population
        if (Pop1 != null) {
            Pop1.draw(ImgObj1, 255);
            labelsObject(Pop1, ImgObj1.getImagePlus());
        }
        if (Pop2 != null) {
            Pop2.draw(ImgObj2, 255);
            labelsObject(Pop2, ImgObj2.getImagePlus());
        }
        if (Pop3 != null) {
            Pop3.draw(ImgObj3, 255);
            labelsObject(Pop3, ImgObj3.getImagePlus());
        }
        
        ImagePlus[] imgColors = {ImgObj2.getImagePlus(), ImgObj3.getImagePlus(), ImgObj1.getImagePlus(), imgCells};
        ImagePlus imgObjects = new RGBStackMerge().mergeHyperstacks(imgColors, true);
        imgObjects.setCalibration(imgCells.getCalibration());
        FileSaver ImgObjectsFile = new FileSaver(imgObjects);
        ImgObjectsFile.saveAsTiff(name); 
        ImgObj1.closeImagePlus(); 
        ImgObj2.closeImagePlus();
        ImgObj3.closeImagePlus();
    }

    
    /**
     * Do Z projection
     * @param img
     * @param projection parameter
     * @return 
     */
    private ImagePlus doZProjection(ImagePlus img, int param) {
        ZProjector zproject = new ZProjector();
        zproject.setMethod(param);
        zproject.setStartSlice(1);
        zproject.setStopSlice(img.getNSlices());
        zproject.setImage(img);
        zproject.doProjection();
       return(zproject.getProjection());
    }
    
    /**
    * Find background image intensity
    * Z project min intensity
    * read mean intensity
    * @param img 
    */
    public double[] find_background(ImagePlus img) {
      double[] bg = new double[2];
      ImagePlus imgProj = doZProjection(img, ZProjector.MIN_METHOD);
      ImageProcessor imp = imgProj.getProcessor();
      bg[0] = imp.getStatistics().mean;
      bg[1] = imp.getStatistics().stdDev;
      System.out.println("Background =  " + bg[0] + "+-" + bg[1]);
      closeImages(imgProj);
      return(bg);
    }
    
    /**
     * Create donut object population
     * 
     */
    public Objects3DPopulation createDonutPop(Objects3DPopulation pop, ImagePlus img, float dilateStepXY, float dilateStepZ) {
        ImagePlus imgCopy = new Duplicator().run(img);
        ImageInt imgBin = ImageInt.wrap(imgCopy);
        Objects3DPopulation donutPop = new Objects3DPopulation();
        Object3D obj, objDil;
        for (int i = 0; i < pop.getNbObjects(); i++) {
            imgBin.fill(0);
            obj = pop.getObject(i);
            objDil = obj.getDilatedObject(dilateStepXY, dilateStepXY, dilateStepZ);
            objDil.draw(imgBin, 255);
            obj.draw(imgBin, 0);
            Objects3DPopulation tmpPop = getPopFromImage(imgBin.getImagePlus());
            donutPop.addObject(tmpPop.getObject(0));
        }
        closeImages(imgCopy);
        imgBin.closeImagePlus();
        return(donutPop);
    }
    
    /**
     * find rois with name = serieName
     */
    public ArrayList<Roi> findRoi(RoiManager rm, String seriesName) {
        ArrayList<Roi> roi = new ArrayList();
        for (int i = 0; i < rm.getCount(); i++) {
            rm.select(i);
            String name = rm.getName(i);
            if (name.contains(seriesName))
                roi.add(rm.getRoi(i));
        }
        return(roi);
    }
    
    
    /**
     * Label object
     * @param popObj
     * @param img 
     */
    public void labelsObject (Objects3DPopulation popObj, ImagePlus img) {
        int fontSize = Math.round(12f/(float)img.getCalibration().pixelWidth);
        Font tagFont = new Font("SansSerif", Font.PLAIN, fontSize);
        for (int n = 0; n < popObj.getNbObjects(); n++) {
            Object3D obj = popObj.getObject(n);
            int[] box = obj.getBoundingBox();
            int z = (int)obj.getCenterZ();
            int x = box[0] - 2;
            int y = box[2] - 2;
            img.setSlice(z+1);
            ImageProcessor ip = img.getProcessor();
            ip.setFont(tagFont);
            ip.setColor(255);
            ip.drawString(Integer.toString(n), x, y);
            img.updateAndDraw();
        }
    }
    
    /**
     * 
     * @param xmlFile
     * @return
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws IOException 
     */
    public ArrayList<Point3D> readXML(String xmlFile, Roi roi) throws ParserConfigurationException, SAXException, IOException {
        ArrayList<Point3D> ptList = new ArrayList<>();
        double x = 0, y = 0 ,z = 0;
        File fXmlFile = new File(xmlFile);
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
	DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
	Document doc = dBuilder.parse(fXmlFile);
        doc.getDocumentElement().normalize();
        NodeList nList = doc.getElementsByTagName("Marker");
        for (int n = 0; n < nList.getLength(); n++) {
            Node nNode = nList.item(n);
            if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                Element eElement = (Element) nNode;
                x = Double.parseDouble(eElement.getElementsByTagName("MarkerX").item(0).getTextContent());
                y = Double.parseDouble(eElement.getElementsByTagName("MarkerY").item(0).getTextContent());
                if (roi != null) {
                    x = x - roi.getXBase();
                    y = y - roi.getYBase();
                }
                z = Double.parseDouble(eElement.getElementsByTagName("MarkerZ").item(0).getTextContent());
            }
            Point3D pt = new Point3D(x, y, z);
            ptList.add(pt);
        }
        return(ptList);
    }
    
}