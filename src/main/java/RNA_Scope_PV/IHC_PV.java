package RNA_Scope_PV;


import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.FileSaver;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import loci.plugins.BF;
import loci.plugins.util.ImageProcessorReader;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.plugin.RGBStackMerge;
import ij.plugin.frame.RoiManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import loci.common.Region;
import loci.plugins.in.ImporterOptions;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.image3d.ImageHandler;
import org.apache.commons.io.FilenameUtils;


/*
 * Find PV, OTX2 and PNN cells
 * 
 */

/**
 *
 * @author phm
 */
public class IHC_PV implements PlugIn {
    
    private final boolean canceled = false;
    private String imageDir = "";
    public  String outDirResults = "";
    public  String rootName = "";
    // threshold to keep PV and Otx2 cells
    public double PVMinInt, Otx2MinInt;
    public double sphCell = 0.5;
    public BufferedWriter PV_Analyze;

    private RNAScope_Tools3D tools = new RNAScope_Tools3D();
    
    /** initialize result files
     * 
     */
    private void writeHeaders() throws IOException {        

                // IHC PV results
                FileWriter fwPV = new FileWriter(outDirResults + "PV_results.xls",false);
                PV_Analyze = new BufferedWriter(fwPV);
                // write results headers
                PV_Analyze.write("Image Name\tLayer name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tPV Mean Intensity\tPV Integrated intensity\tPV Mean background Int\t"
                        + "Std backgroun Int\tPV Corrected Integrated intensity\n");
                PV_Analyze.flush();

    }
    
    public void saveIHCObjects(Objects3DPopulation PVPop, ImagePlus imgCells, String name) {
       // PVPop blue Otx2Pop/Tomato red PNNPop green
        ImageHandler pvImgObj = ImageHandler.wrap(imgCells).createSameDimensions();
        // draw obj population
        if (PVPop != null) {
            PVPop.draw(pvImgObj, 64);
            tools.labelsObject(PVPop, pvImgObj.getImagePlus());
        }
       
        ImagePlus[] imgColors = {null, pvImgObj.getImagePlus(), null, imgCells};
        ImagePlus imgObjects = new RGBStackMerge().mergeHyperstacks(imgColors, true);
        imgObjects.setCalibration(imgCells.getCalibration());
        FileSaver ImgObjectsFile = new FileSaver(imgObjects);
        ImgObjectsFile.saveAsTiff(name); 
        pvImgObj.closeImagePlus(); 
    }
    
    @Override
    public void run(String arg) {
        try {
            tools.pnn = false;
            if (canceled) {
                IJ.showMessage(" Pluging canceled");
                return;
            }
            imageDir = tools.dialog();
            if (imageDir == null) {
                return;
            }
            if (tools.stardist && !new File(tools.starDistModel).exists()) {
                IJ.showMessage("No stardist model found, plugin canceled");
                return;
            }
            // Find images with nd extension
            ArrayList<String> imageFile = tools.findImages(imageDir);
            if (imageFile == null) {
                IJ.showMessage("Error", "No images found with lif extension");
                return;
            }
            // create output folder
            outDirResults = imageDir + File.separator+ "Results"+ File.separator;
            File outDir = new File(outDirResults);
            if (!Files.exists(Paths.get(outDirResults))) {
                outDir.mkdir();
            }
            
            // create OME-XML metadata store of the latest schema version
            ServiceFactory factory;
            factory = new ServiceFactory();
            OMEXMLService service = factory.getInstance(OMEXMLService.class);
            IMetadata meta = service.createOMEXMLMetadata();
            ImageProcessorReader reader = new ImageProcessorReader();
            reader.setMetadataStore(meta);
            reader.setId(imageFile.get(0));
            
            // write headers
            writeHeaders();
                        
            // Find image calibration
            Calibration cal = tools.findImageCalib(meta);

            // write headers
            writeHeaders();
            
            
             /** 
             * 
             * Detect IHC PV cells, measure intensity in PV channel2 and Otx2 channel1
             * Detect Otx2 cells measure intensity in Otx2 and PV channels
            */
             
            for (String f : imageFile) {
                rootName = FilenameUtils.getBaseName(f);  
                ImporterOptions options = new ImporterOptions();
                
                 // Roi
                String rootFilename =  imageDir+ File.separator + rootName;
                RoiManager rm = new RoiManager(false);
                String roiFile = new File(rootFilename + ".zip").exists() ? rootFilename + ".zip" : rootFilename + ".roi";
                
                if (new File(roiFile).exists())
                    rm.runCommand("Open", roiFile);
                
                int series=  reader.getSeriesCount();
                // for all series
                for (int s = 0; s < series; s++) {
                    options.setId(f);
                    options.setSplitChannels(true);
                    options.setQuiet(true);
                    options.setCrop(true);
                    options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                    String seriesName = meta.getImageName(s);
                    options.setSeriesOn(s, true);
                    
                   // if no Roi select all image
                    if (!new File(roiFile).exists()) {
                        rm.add(new Roi(0, 0, reader.getSizeX(), reader.getSizeY()),0);
                        rm.getRoi(0).setName(seriesName);
                    }
                    // for all rois
                    for (int r = 0; r < rm.getCount(); r++) {
                        Roi roi = rm.getRoi(r);
                        String roiName = roi.getName();
                        if (roiName.contains(seriesName)) {
                            // Find crop region
                            Rectangle rect = roi.getBounds();
                            Region reg = new Region(rect.x, rect.y, rect.width, rect.height);
                            options.setCropRegion(s, reg);
                            roi.setLocation(0, 0);
                            
                            //PV
                            System.out.println("Opening PV channel ...");
                            ImagePlus imgPV = BF.openImagePlus(options)[0];
                            
                            //section volume in mm^3
                            double sectionVol = (imgPV.getWidth() * cal.pixelWidth * imgPV.getHeight() * cal.pixelHeight * imgPV.getNSlices() * cal.pixelDepth)/1e9;
                            // PV background
                            double[] bgPV = tools.find_background(imgPV);
                            
                            // find PV cells                          
                            Objects3DPopulation PVPop = new Objects3DPopulation();
                            if (tools.stardist)
                                PVPop = tools.stardistCellsPop(imgPV);
                            else
                                PVPop = tools.findCells(imgPV, roi, 10, 12, 1, "MeanPlusStdDev", true, 10, 1);
                            System.out.println("PV Cells found : " + PVPop.getNbObjects() + " in " + roiName);

                            // save image for objects population
                            saveIHCObjects(PVPop, imgPV, outDirResults+rootName+"-"+roiName+"_IHCObjects.tif");   
                            
                            // Compute parameters

                            // PV
                            ImageHandler imhPV = ImageHandler.wrap(imgPV);
                            for (int o = 0; o < PVPop.getNbObjects(); o++) {
                                Object3D obj = PVPop.getObject(o);
                                double objVol = obj.getVolumeUnit();
                                double objIntPV = obj.getIntegratedDensity(imhPV);
                                double objMeanPV = obj.getPixMeanValue(imhPV);
                                PV_Analyze.write(rootName+"\t"+roiName+"\t"+sectionVol+"\t"+PVPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objMeanPV+"\t"+objIntPV+"\t"+
                                        bgPV[0]+"\t"+ bgPV[1] + "\t" + (objIntPV - (bgPV[0] * obj.getVolumePixels()))+"\n");
                                PV_Analyze.flush();
                            }

                            tools.closeImages(imgPV);
                        }
                    }
                    options.setSeriesOn(s, false);
                }
            }
            if (PV_Analyze != null) {
                PV_Analyze.close();
            }
            
        } catch (IOException | DependencyException | ServiceException | FormatException  ex) {
            Logger.getLogger(IHC_PV.class.getName()).log(Level.SEVERE, null, ex);
        }
        IJ.showStatus("Process done ...");
    }
}
