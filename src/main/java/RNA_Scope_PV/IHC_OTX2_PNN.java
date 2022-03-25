/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
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
 * Find OTX2 and PNN cells 
 * 
 * @author phm
 */


public class IHC_OTX2_PNN implements PlugIn {
    
    private final boolean canceled = false;
    private String imageDir = "";
    public  String outDirResults = "";
    public  String rootName = "";
    // threshold to keep OTX2 cells
    public double OTX2MinInt;
    public double sphCell = 0.5;
    public BufferedWriter OTX2_Analyze, PNN_Analyze;
    
    private RNAScope_Tools3D tools = new RNAScope_Tools3D();
    
    /** initialize result files
     * 
     */
    private void writeHeaders() throws IOException {        

                // IHC OTX2 results
                FileWriter fwOTX2 = new FileWriter(outDirResults + "OTX2_results.xls",false);
                OTX2_Analyze = new BufferedWriter(fwOTX2);
                // write results headers
                OTX2_Analyze.write("Image Name\tSeries name\tLayer name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tOTX2 Mean Intensity\tOTX2 Integrated intensity\tOTX2 Mean background Int\t"
                        + "Std backgroun Int\tOTX2 Corrected Integrated intensity\tPNN Corrected Integrated intensity\n");
                OTX2_Analyze.flush();

                 // IHC PNN results
                FileWriter fwPNN = new FileWriter(outDirResults + "PNN_results.xls",false);
                PNN_Analyze = new BufferedWriter(fwPNN);
                // write results headers
                PNN_Analyze.write("Image Name\tSeries Name\tLayer name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tPNN Integrated intensity\tPNN Mean background Int\t"
                        + "Std background Int\tPNN Corrected Integrated intensity\t#OTX2 Cell\tOTX2 Corrected Integrated intensity\n");
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
            ArrayList<String> imageFile = tools.findImages(imageDir, "lif");
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
            
            // Find channel names
            String[] channels = tools.findChannels(imageFile.get(0), meta, reader);
            
            // Find image calibration
            Calibration cal = tools.findImageCalib(meta);
            
            // Channels dialog
            int[] channelIndex = new int[channels.length];
            List<String> channelsName = new ArrayList();
            channelsName.add("PNN");
            channelsName.add("OTX2");
            
            if (channels.length > 1) {
                channelIndex = tools.dialog(channels, channelsName);
                if (channelIndex == null) {
                    IJ.showStatus("Plugin cancelled");
                    return;
                }
            }
            if (tools.stardist && !new File(tools.starDistModel).exists()) {
                IJ.showMessage("No stardist model found, plugin canceled");
                return;
            }
            // write headers
            writeHeaders();
            
            for (String f : imageFile) {
                rootName = FilenameUtils.getBaseName(f);
                // Roi
                RoiManager rm = new RoiManager(false);
                String roiFile = new File(rootName + ".zip").exists() ? rootName + ".zip" : rootName + ".roi";
                // if roi file exists open it else roi = select all
                if (new File(roiFile).exists())
                    rm.runCommand("Open", roiFile);
                

                ImporterOptions options = new ImporterOptions();
                int series=  reader.getSeriesCount();
                // for all series
                for (int s = 0; s < series; s++) {
                    String seriesName = meta.getImageName(s);
                    // Find xml points file
                    String xmlFile = imageDir+ File.separator + rootName + "_" + seriesName + ".xml";
                    if (!new File(xmlFile).exists()) {
                        IJ.showStatus("No XML file found !") ;
                    }
                    else {
                        
                        /** 
                         * read lif
                         * Detect IHC OTX2 cells channel 0, measure intensity
                         * compute donut OTX2 Object and measure in PNN channel 2
                         * Detect PNN cells measure intensity in PNN channel and find corresponding OTX2 Cell
                        */
                        options.setId(f);
                        options.setSplitChannels(true);
                        options.setQuiet(true);
                        options.setCrop(true);
                        options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                        options.setSeriesOn(s, true);

                        // if no Roi file select all image
                        if (!new File(roiFile).exists()) {
                            rm.add(new Roi(0, 0, reader.getSizeX(), reader.getSizeY()),0);
                            rm.getRoi(0).setName(seriesName);
                        }
                        // for all rois
                        for (int r = 0; r < rm.getCount(); r++) {
                            Roi roi = rm.getRoi(r);
                            String roiName = roi.getName();

                            // if roi name contains series name
                            // Find crop region
                            if (roiName.contains(seriesName)) {
                                Rectangle rect = roi.getBounds();
                                Region reg = new Region(rect.x, rect.y, rect.width, rect.height);
                                options.setCropRegion(s, reg);


                                // Find PNN cells with xml points file
                                ArrayList<Point3D> PNNPoints = tools.readXML(xmlFile, roi);
                                roi.setLocation(0, 0);

                                // PNN
                                System.out.println("Series : "+seriesName+" ROI : "+roiName);
                                System.out.println("Opening PNN channel ...");
                                options.setCBegin(0, channelIndex[0]);
                                options.setCEnd(0, channelIndex[0]); 
                                ImagePlus imgPNN = BF.openImagePlus(options)[0];
                                // PNN background
                                double[] bgPNN = tools.find_background(imgPNN);
                                tools.median_filter(imgPNN, 1);
                                Objects3DPopulation PNNPop = new Objects3DPopulation();
                                if (tools.pnnDetection.equals("ridge"))
                                    PNNPop = tools.findPNNCellsRidge(imgPNN, roi, PNNPoints);
                                else
                                    PNNPop = tools.findPNNCellsOutliner(imgPNN, roi, PNNPoints);
                                System.out.println("PNN Cells found : " + PNNPop.getNbObjects());

                                //OTX2
                                System.out.println("Opening OTX2 channel ...");
                                options.setCBegin(0, channelIndex[1]);
                                options.setCEnd(0, channelIndex[1]); 
                                ImagePlus imgOTX2 = BF.openImagePlus(options)[0];
                                //section volume in mm^3
                                double sectionVol = (imgOTX2.getWidth() * cal.pixelWidth * imgOTX2.getHeight() * cal.pixelHeight * imgOTX2.getNSlices() * cal.pixelDepth)/1e9;
                                // OTX2 background
                                double[] bgOTX2 = tools.find_background(imgOTX2);
                                // find OTX2 cells                          
                                Objects3DPopulation OTX2Pop = new Objects3DPopulation();
                                if (tools.stardist)
                                    OTX2Pop = tools.stardistCellsPop(imgOTX2);
                                else
                                    OTX2Pop = tools.findCells(imgOTX2, roi, 4, 6, 1, "MeanPlusStdDev", false, 0, 1);
                                System.out.println("OTX2 Cells found : " + OTX2Pop.getNbObjects());

                                // save image for objects population
                                tools.saveIHCObjects(OTX2Pop, null, PNNPop, imgOTX2, outDirResults+rootName+"-"+seriesName+"_"+roiName+"_IHCObjects.tif");    

                                // Compute parameters

                                // OTX2
                                // create donut
                                float dilatedStepXY = (float) (6/cal.pixelWidth);
                                float dilatedStepZ = (float) (6/cal.pixelDepth);
                                Objects3DPopulation OTX2DonutPop  = tools.createDonutPop(OTX2Pop, imgOTX2, dilatedStepXY, dilatedStepZ);
                                ImageHandler imhOTX2 = ImageHandler.wrap(imgOTX2);
                                ImageHandler imhPNN = ImageHandler.wrap(imgPNN);
                                for (int o = 0; o < OTX2Pop.getNbObjects(); o++) {
                                    Object3D obj = OTX2Pop.getObject(o);
                                    Object3D objDonut = OTX2DonutPop.getObject(o);
                                    double objVol = obj.getVolumeUnit();
                                    double objIntOTX2 = obj.getIntegratedDensity(imhOTX2);
                                    double objMeanOTX2 = obj.getPixMeanValue(imhOTX2);
                                    double objIntPNN = objDonut.getIntegratedDensity(imhPNN);
                                    OTX2_Analyze.write(rootName+"\t"+seriesName+"\t"+roiName+"\t"+sectionVol+"\t"+OTX2Pop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objMeanOTX2+"\t"+objIntOTX2+"\t"+
                                            bgOTX2[0]+"\t"+ bgOTX2[1] + "\t" + (objIntOTX2 - (bgOTX2[0] * obj.getVolumePixels()))+"\t"+(objIntPNN - (bgPNN[0] * objDonut.getVolumePixels()))+"\n");
                                    OTX2_Analyze.flush();
                                }

                                 // PNN
                                for (int o = 0; o < PNNPop.getNbObjects(); o++) {
                                    Object3D obj = PNNPop.getObject(o);
                                    double objVol = obj.getVolumeUnit();
                                    double objIntPNN = obj.getIntegratedDensity(imhPNN);
                                    // find associated pv cell
                                    Object3D pvCell = tools.findAssociatedCell(OTX2Pop, obj);
                                    double objIntOTX2 = 0;
                                    int pvIndex = -1;
                                    if (pvCell != null) {
                                        objIntOTX2 = pvCell.getIntegratedDensity(imhOTX2) - (bgOTX2[0] * pvCell.getVolumePixels());
                                        pvIndex = OTX2Pop.getIndexOf(pvCell);
                                    }    
                                    PNN_Analyze.write(rootName+"\t"+seriesName+"\t"+roiName+"\t"+sectionVol+"\t"+PNNPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objIntPNN+"\t"+
                                            bgPNN[0]+"\t"+bgPNN[1]+"\t"+(objIntPNN - bgPNN[0] * obj.getVolumePixels())+"\t"+pvIndex+"\t"+objIntOTX2+"\n");
                                    PNN_Analyze.flush();
                                }
                                tools.closeImages(imgPNN);
                                tools.closeImages(imgOTX2);
                            }
                        }
                    }
                    options.setSeriesOn(s, false);
                }
            }
            if (PNN_Analyze != null) {
                OTX2_Analyze.close();
                PNN_Analyze.close();
            }
        } catch (DependencyException | ServiceException | FormatException | IOException | ParserConfigurationException | SAXException ex) {
            Logger.getLogger(IHC_OTX2_PNN.class.getName()).log(Level.SEVERE, null, ex);
        }
        IJ.showStatus("Process done ...");
    }
    
}
