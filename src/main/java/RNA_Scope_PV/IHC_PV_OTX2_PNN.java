package RNA_Scope_PV;


import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
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
import ij.plugin.frame.RoiManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import loci.common.Region;
import loci.plugins.in.ImporterOptions;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.geom.Point3D;
import mcib3d.image3d.ImageHandler;
import org.apache.commons.io.FilenameUtils;
import org.xml.sax.SAXException;


/*
 * Find PV, OTX2 and PNN cells
 * 
 */

/**
 *
 * @author phm
 */
public class IHC_PV_OTX2_PNN implements PlugIn {
    
    private final boolean canceled = false;
    private String imageDir = "";
    public  String outDirResults = "";
    public  String rootName = "";
    // threshold to keep PV and Otx2 cells
    public double PVMinInt, Otx2MinInt;
    public double sphCell = 0.5;
    public BufferedWriter PV_Analyze, Otx2_Analyze, PNN_Analyze;
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
                        + "Std backgroun Int\tPV Corrected Integrated intensity\tOtx2 Corrected Integrated intensity\tPNN Corrected Integrated intensity\n");
                PV_Analyze.flush();

                // IHC Otx2 results
                FileWriter fwOtx2 = new FileWriter(outDirResults + "Otx2_results.xls",false);
                Otx2_Analyze = new BufferedWriter(fwOtx2);
                // write results headers
                Otx2_Analyze.write("Image Name\tLayer name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tOtx2 Integrated intensity\tOtx2 Mean background Int\t"
                        + "Std background Int\tOtx2 Corrected Integrated intensity\tPV Corrected Integrated intensity\tPNN Corrected Integrated intensity\n");
                Otx2_Analyze.flush();

                 // IHC PNN results
                FileWriter fwPNN = new FileWriter(outDirResults + "PNN_results.xls",false);
                PNN_Analyze = new BufferedWriter(fwPNN);
                // write results headers
                PNN_Analyze.write("Image Name\tLayer name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tPNN Integrated intensity\tPNN Mean background Int\t"
                        + "Std background Int\tPNN Corrected Integrated intensity\t#PV Cell\tPV Corrected Integrated intensity\t#Otx2 Cell\tOtx2 Corrected Integrated intensity\n");
                PNN_Analyze.flush();
    }
    
    
    
    @Override
    public void run(String arg) {
        try {
            tools.pnn = true;
            if (canceled) {
                IJ.showMessage(" Pluging canceled");
                return;
            }
            imageDir = IJ.getDirectory("Choose Directory Containing Image Files...");
            if (imageDir == null) {
                return;
            }
            // Find images with nd extension
            ArrayList<String> imageFile = tools.findImages(imageDir);
            if (imageFile == null) {
                IJ.showMessage("Error", "No images found with nd extension");
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
            
            // Find channel names
            String[] channels = tools.findChannels(imageFile.get(0), meta, reader);
            
            // Find image calibration
            Calibration cal = tools.findImageCalib(meta);

            // write headers
            writeHeaders();
            
            // Channels dialog
            int[] channelIndex = new int[channels.length];
            List<String> channelsName = new ArrayList();
            channelsName.add("OTX2");
            channelsName.add("PNN");
            channelsName.add("PV");
            if (channels.length > 3)
                channelsName.add("DAPI");
            
            if (channels.length > 1) {
                channelIndex = tools.dialog(channels, channelsName);
                if ( channelIndex == null) {
                    IJ.showStatus("Plugin cancelled");
                    return;
                }
            }
            if (tools.stardist && !new File(tools.starDistModel).exists()) {
                IJ.showMessage("No stardist model found, plugin canceled");
                return;
            }
            for (String f : imageFile) {
                rootName = FilenameUtils.getBaseName(f);  
                ImporterOptions options = new ImporterOptions();



                /** 
                 * 
                 * Detect IHC PV cells, measure intensity in PV channel2 and Otx2 channel1
                 * compute donut PV Object and measure in PNN channel0
                 * Detect Otx2 cells measure intensity in Otx2 and PV channels
                 * Detect PNN cells measure intensity in PNN channel and find corresponding PV Cell
                */

                String rootFilename =  imageDir+ File.separator + rootName;
                 // Roi
                RoiManager rm = new RoiManager(false);
                String roiFile = new File(rootFilename + ".zip").exists() ? rootFilename + ".zip" : rootFilename + ".roi";
                if (new File(roiFile).exists())
                    rm.runCommand("Open", roiFile);
                else {
                    rm.add(new Roi(0, 0, reader.getSizeX(), reader.getSizeY()),0);
                    rm.getRoi(0).setName("all");
                }
                // Find xml points file
                String xmlFile = rootFilename + ".xml";
                if (!new File(xmlFile).exists())
                    IJ.showStatus("No XML file found !") ;

                    options.setId(f);
                    options.setSplitChannels(true);
                    options.setQuiet(true);
                    options.setCrop(true);
                    options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                    
                    // for all rois
                    for (int r = 0; r < rm.getCount(); r++) {
                        Roi roi = rm.getRoi(r);
                        String roiName = roi.getName();
                        // Find crop region
                        Rectangle rect = roi.getBounds();
                        Region reg = new Region(rect.x, rect.y, rect.width, rect.height);
                        options.setCropRegion(0, reg);

                        // Find PNN cells with xml points file
                        ArrayList<Point3D> PNNPoints = tools.readXML(xmlFile, roi);
                        roi.setLocation(0, 0);

                        // PNN
                        System.out.println(" ROI : "+roiName);
                        System.out.println("Opening PNN channel ...");
                        options.setCBegin(0, channelIndex[1]);
                        options.setCEnd(0, channelIndex[1]); 
                        ImagePlus imgPNN = BF.openImagePlus(options)[0];
                        // PNN background
                        double[] bgPNN = tools.find_background(imgPNN);
                        Objects3DPopulation PNNPop = new Objects3DPopulation();
                        if (tools.pnnDetection.equals("Ridge"))
                                PNNPop = tools.findPNNCellsRidge(imgPNN, roi, PNNPoints);
                        else
                            PNNPop = tools.findPNNCellsOutliner(imgPNN, roi, PNNPoints);
                        
                        System.out.println("PNN Cells found : " + PNNPop.getNbObjects() + " in " + roiName);

                        //PV
                        System.out.println("Opening PV channel ...");
                        options.setCBegin(0, channelIndex[2]);
                        options.setCEnd(0, channelIndex[2]);
                        ImagePlus imgPV = BF.openImagePlus(options)[0];
                        //section volume in mm^3
                        double sectionVol = (imgPV.getWidth() * cal.pixelWidth * imgPV.getHeight() * cal.pixelHeight * imgPV.getNSlices() * cal.pixelDepth)/1e9;
                        // PV background
                        double[] bgPV = tools.find_background(imgPV);
                        // find PV cells 
                        Objects3DPopulation PVPop = new Objects3DPopulation();
                        /// ??????????? On compte pas PV si pas X63 ??????????????
                        if (!tools.obj63)
                            if (tools.stardist)
                                PVPop = tools.stardistCellsPop(imgPV);
                            else
                                PVPop = tools.findCells(imgPV, roi, 18, 20, 1, "MeanPlusStdDev", true, 10, 1);
                        System.out.println("PV Cells found : " + PVPop.getNbObjects() + " in " + roiName);

                        //Otx2
                        System.out.println("Opening Otx2 channel ...");
                        options.setCBegin(0, channelIndex[0]);
                        options.setCEnd(0, channelIndex[0]);
                        ImagePlus imgOtx2 = BF.openImagePlus(options)[0];
                        // Otx2 background
                        double[] bgOtx2 = tools.find_background(imgOtx2);
                        // Find Otx2 cells
                        Objects3DPopulation Otx2Pop = new Objects3DPopulation();
                        if (tools.obj63) {
                            if (tools.stardist)
                                Otx2Pop = tools.stardistCellsPop(imgOtx2);
                            else
                                Otx2Pop = tools.findCells(imgOtx2, roi, 15, 25, 4, "Huang", false, 10, 3);
                            tools.filterCells(Otx2Pop, 0.55);
                        }
                        else {
                            if (tools.stardist)
                                Otx2Pop = tools.stardistCellsPop(imgOtx2);
                            else
                                Otx2Pop = tools.findCells(imgOtx2, roi, 18, 20, 1, "Huang", true, 10, 3);
                            tools.filterCells(Otx2Pop, 0.55);
                        }
                        System.out.println("Otx2 Cells found : " + Otx2Pop.getNbObjects()  + " in " + roiName);

                        // save image for objects population
                        tools.saveIHCObjects(PVPop, Otx2Pop, PNNPop, imgPV, outDirResults+rootName+"-"+roiName+"_IHCObjects.tif");    

                        // Compute parameters

                        // PV
                        // create donut
                        float dilatedStepXY = (float) (6/cal.pixelWidth);
                        float dilatedStepZ = (float) (6/cal.pixelDepth);
                        Objects3DPopulation PVDonutPop  = tools.createDonutPop(PVPop, imgPV, dilatedStepXY, dilatedStepZ);
                        ImageHandler imhPV = ImageHandler.wrap(imgPV);
                        ImageHandler imhOtx2 = ImageHandler.wrap(imgOtx2);
                        ImageHandler imhPNN = ImageHandler.wrap(imgPNN);
                        for (int o = 0; o < PVPop.getNbObjects(); o++) {
                            Object3D obj = PVPop.getObject(o);
                            Object3D objDonut = PVDonutPop.getObject(o);
                            double objVol = obj.getVolumeUnit();
                            double objIntPV = obj.getIntegratedDensity(imhPV);
                            double objMeanPV = obj.getPixMeanValue(imhPV);
                            double objIntOtx2 = obj.getIntegratedDensity(imhOtx2);
                            double objIntPNN = objDonut.getIntegratedDensity(imhPNN);
                            PV_Analyze.write(rootName+"\t"+roiName+"\t"+sectionVol+"\t"+PVPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objMeanPV+"\t"+objIntPV+"\t"+
                                    bgPV[0]+"\t"+ bgPV[1] + "\t" + (objIntPV - (bgPV[0] * obj.getVolumePixels()))+"\t"+(objIntOtx2 - (bgOtx2[0] * objVol))+"\t"+
                                    (objIntPNN - (bgPNN[0] * objDonut.getVolumePixels()))+"\n");
                            PV_Analyze.flush();
                        }

                        // Otx2
                        Objects3DPopulation Otx2DonutPop  = tools.createDonutPop(Otx2Pop, imgOtx2, dilatedStepXY, dilatedStepZ);
                        for (int o = 0; o < Otx2Pop.getNbObjects(); o++) {
                            Object3D obj = Otx2Pop.getObject(o);
                            Object3D objDonut = Otx2DonutPop.getObject(o);
                            double objVol = obj.getVolumeUnit();
                            double objIntPV = obj.getIntegratedDensity(imhPV);
                            double objIntOtx2 = obj.getIntegratedDensity(imhOtx2);
                            double objIntPNN = objDonut.getIntegratedDensity(imhPNN);
                            Otx2_Analyze.write(rootName+"\t"+roiName+"\t"+sectionVol+"\t"+Otx2Pop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objIntOtx2+"\t"+
                                    bgOtx2[0]+"\t"+bgOtx2[1]+"\t"+(objIntOtx2 - (bgOtx2[0] * obj.getVolumePixels()))+"\t"+(objIntPV - (bgPV[0] * obj.getVolumePixels()))+"\t"+
                                    (objIntPNN - (bgPNN[0] * objDonut.getVolumePixels()))+"\n");
                            Otx2_Analyze.flush();
                        }

                        // PNN
                        for (int o = 0; o < PNNPop.getNbObjects(); o++) {
                            Object3D obj = PNNPop.getObject(o);
                            double objVol = obj.getVolumeUnit();
                            double objIntPNN = obj.getIntegratedDensity(imhPNN);
                            // find associated pv cell
                            Object3D pvCell = tools.findAssociatedCell(PVPop, obj);
                            // find associated Otx2 cell
                            Object3D Otx2Cell = tools.findAssociatedCell(Otx2Pop, obj);
                            double objIntPV = 0;
                            double objIntOtx2 = 0;
                            int pvIndex = -1;
                            int Otx2Index = -1;
                            if (pvCell != null) {
                                objIntPV = pvCell.getIntegratedDensity(imhPV) - (bgPV[0] * pvCell.getVolumePixels());
                                pvIndex = PVPop.getIndexOf(pvCell);
                            }    
                            if (Otx2Cell != null) {
                                objIntOtx2 = Otx2Cell.getIntegratedDensity(imhOtx2) - bgOtx2[0] * (Otx2Cell.getVolumePixels());
                                Otx2Index = Otx2Pop.getIndexOf(Otx2Cell);
                            }
                            PNN_Analyze.write(rootName+"\t"+roiName+"\t"+sectionVol+"\t"+PNNPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objIntPNN+"\t"+
                                    bgPNN[0]+"\t"+bgPNN[1]+"\t"+(objIntPNN - bgPNN[0] * obj.getVolumePixels())+"\t"+pvIndex+"\t"+objIntPV+
                                    "\t"+Otx2Index+"\t"+objIntOtx2+"\n");
                            PNN_Analyze.flush();
                        }
                        tools.closeImages(imgPNN);
                        tools.closeImages(imgOtx2);
                        tools.closeImages(imgPV);
                    }
                }
                if (PV_Analyze != null) {
                    PV_Analyze.close();
                    Otx2_Analyze.close();
                    PNN_Analyze.close();
                }
            
            } catch (IOException | DependencyException | ServiceException | FormatException | ParserConfigurationException | SAXException ex) {
                Logger.getLogger(IHC_PV_OTX2_PNN.class.getName()).log(Level.SEVERE, null, ex);
            }
        IJ.showStatus("Process done ...");
    }
}
