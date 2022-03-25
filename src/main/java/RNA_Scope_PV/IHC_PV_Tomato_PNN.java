package RNA_Scope_PV;


import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
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
public class IHC_PV_Tomato_PNN implements PlugIn {
    
    private final boolean canceled = false;
    private String imageDir = "";
    public  String outDirResults = "";
    public  String rootName = "";
    // threshold to keep PV and Tomato cells
    public double PVMinInt, TomatoMinInt;
    public double sphCell = 0.5;
    public BufferedWriter PV_Analyze, Tomato_Analyze, PNN_Analyze;

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
                        + "Std backgroun Int\tPV Corrected Integrated intensity\tTomato Corrected Integrated intensity\tPNN Corrected Integrated intensity\n");
                PV_Analyze.flush();
                
             // IHC PNN results
                FileWriter fwPNN = new FileWriter(outDirResults + "PNN_results.xls",false);
                PNN_Analyze = new BufferedWriter(fwPNN);
                // write results headers
                PNN_Analyze.write("Image Name\tLayer name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tPNN Integrated intensity\tPNN Mean background Int\t"
                        + "Std background Int\tPNN Corrected Integrated intensity\t#PV Cell\tPV Corrected Integrated intensity\t#Tomato Cell\tTomato Corrected Integrated intensity\n");
                PNN_Analyze.flush();    

            // IHC Tomato results
                FileWriter fwTomato = new FileWriter(outDirResults + "Tomato_results.xls",false);
                Tomato_Analyze = new BufferedWriter(fwTomato);
                // write results headers
                Tomato_Analyze.write("Image Name\tLayer name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tTomato cell integrated intensity\tTomato cell Mean background Int\t"
                        + "Tomato cell Std background Int\tTomato cell corrected integrated intensity\tTomato Cell corrected integrated intensity in PV channel\tTomato Cell corrected integrated intensity in PNN channel\t"
                        + "PV cell index\tPV cell corrected integrated intensity in PV channel\tPNN cell index\tPNN cell corrected integrated intensity in PNN channel\n");
                Tomato_Analyze.flush();
            
           

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
            
            // Find channel names
            String[] channels = tools.findChannels(imageFile.get(0), meta, reader);
            
            // Find image calibration
            Calibration cal = tools.findImageCalib(meta);

            // write headers
            writeHeaders();
            
            // Channels dialog
            int[] channelIndex = new int[channels.length];
            List<String> channelsName = new ArrayList();
            channelsName.add("Tomato");
            channelsName.add("PNN");
            channelsName.add("PV");
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
            // write headers
            writeHeaders();
            
            for (String f : imageFile) {
                rootName = FilenameUtils.getBaseName(f);
                ImporterOptions options = new ImporterOptions();

                /** 
                 * read nd
                 * Detect IHC PV cells, measure intensity in PV channel2 and Tomato channel1
                 * compute donut PV Object and measure in PNN channel0
                 * Detect Tomato cells measure intensity in Tomato and PV channels
                 * Detect PNN cells measure intensity in PNN channel and find corresponding PV Cell
                */

                   // Find xml points file
                    String rootFilename =  imageDir+ File.separator + rootName;
                    String xmlFile = rootFilename + ".xml";
                    String roiFile = new File(rootFilename + ".zip").exists() ? rootFilename + ".zip" : rootFilename + ".roi";
                    if (!new File(xmlFile).exists()) {
                        IJ.showStatus("No XML file found !") ;
                    }
                    else {
                        options.setId(f);
                        options.setSplitChannels(true);
                        options.setQuiet(true);
                        options.setCrop(true);
                        options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);


                        // Roi
                        RoiManager rm = new RoiManager(false);
                        if (new File(roiFile).exists())
                            rm.runCommand("Open", roiFile);
                        else {
                            rm.add(new Roi(0, 0, reader.getSizeX(), reader.getSizeY()),0);
                            rm.getRoi(0).setName("all");
                        }

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
                            System.out.println("ROI : "+roiName);
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
                            if (tools.stardist)
                                PVPop = tools.stardistCellsPop(imgPV);
                            else
                                PVPop = tools.findCells(imgPV, roi, 18, 20, 1, "MeanPlusStdDev", true, 20, 1);
                            System.out.println("PV Cells found : " + PVPop.getNbObjects() + " in " + roiName);

                            //Tomato
                            System.out.println("Opening Tomato channel ...");
                            options.setCBegin(0, channelIndex[0]);
                            options.setCEnd(0, channelIndex[0]); 
                            ImagePlus imgTomato = BF.openImagePlus(options)[0];
                            
                            // Tomato background
                            double[] bgTomato = tools.find_background(imgTomato);
                            // Find Tomato cells
                            Objects3DPopulation TomatoPop = new Objects3DPopulation();
                            if (tools.stardist)
                                TomatoPop = tools.stardistCellsPop(imgTomato);
                            else
                                TomatoPop = tools.findCells(imgTomato, roi, 18, 20, 1, "MeanPlusStdDev", true, 20, 3);
                            tools.filterCells(TomatoPop, 0.55);
                            System.out.println("Tomato Cells found : " + TomatoPop.getNbObjects()  + " in " + roiName);

                            // save image for objects population
                            tools.saveIHCObjects(PVPop, TomatoPop, PNNPop, imgPV, outDirResults+rootName+"-"+roiName+"_IHCObjects.tif");  
                            


                            // Compute parameters

                            // create donut
                            float dilatedStepXY = (float) (3/cal.pixelWidth);
                            float dilatedStepZ = 3;

                            ImageHandler imhPV = ImageHandler.wrap(imgPV);
                            ImageHandler imhTomato = ImageHandler.wrap(imgTomato);
                            ImageHandler imhPNN = ImageHandler.wrap(imgPNN);

                            // PV
                        
                            Objects3DPopulation PVDonutPop  = tools.createDonutPop(PVPop, imgPV, dilatedStepXY, dilatedStepZ);
                            for (int o = 0; o < PVPop.getNbObjects(); o++) {
                                Object3D obj = PVPop.getObject(o);
                                Object3D objDonut = PVDonutPop.getObject(o);
                                double objVol = obj.getVolumeUnit();
                                double objIntPV = obj.getIntegratedDensity(imhPV);
                                double objMeanPV = obj.getPixMeanValue(imhPV);
                                double objIntTomato = obj.getIntegratedDensity(imhTomato);
                                double objIntPNN = objDonut.getIntegratedDensity(imhPNN);
                                PV_Analyze.write(rootName+"\t"+roiName+"\t"+sectionVol+"\t"+PVPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objMeanPV+"\t"+objIntPV+"\t"+
                                        bgPV[0]+"\t"+ bgPV[1] + "\t" + (objIntPV - (bgPV[0] * obj.getVolumePixels()))+"\t"+(objIntTomato - (bgTomato[0] * objVol))+"\t"+
                                        (objIntPNN - (bgPNN[0] * objDonut.getVolumePixels()))+"\n");
                                PV_Analyze.flush();
                            }
                            
                            // Tomato
                            Objects3DPopulation TomatoDonutPop  = tools.createDonutPop(TomatoPop, imgTomato, dilatedStepXY, dilatedStepZ);
                            for (int o = 0; o < TomatoPop.getNbObjects(); o++) {
                                Object3D tomatoCell = TomatoPop.getObject(o);
                                double tomatoCellVol = tomatoCell.getVolumeUnit();
                                // find associated PV cell and integrated intensity
                                Object3D pvCell = tools.findAssociatedCell(PVPop, tomatoCell);
                                int pvCellIndex = -1;
                                double pvCellIntChPVCor = 0;
                                if (pvCell != null) {
                                    pvCellIntChPVCor = pvCell.getIntegratedDensity(imhPV) - (bgPV[0] * pvCell.getVolumePixels());
                                    pvCellIndex = PVPop.getIndexOf(pvCell);
                                }
                                // find associated PNN cell and integrated intensity
                                Object3D pnnCell = tools.findAssociatedCell(PNNPop, tomatoCell);
                                int pnnCellIndex = -1;
                                double pnnCellIntChPNNCor = 0;

                                if (pnnCell != null) {
                                    pnnCellIntChPNNCor = pnnCell.getIntegratedDensity(imhPNN) - (bgPNN[0] * pnnCell.getVolumePixels());
                                    pnnCellIndex = PNNPop.getIndexOf(pnnCell);
                                }
                                // Find tomato integrated intensity in PV and PNN channel
                                Object3D tomatoCellDonut = TomatoDonutPop.getObject(o);
                                double tomatoCellIntChTomato = tomatoCell.getIntegratedDensity(imhTomato);
                                double tomatoCellIntChTomatoCor = tomatoCellIntChTomato - (bgTomato[0] * tomatoCell.getVolumePixels());
                                double tomatoCellIntChPVCor = tomatoCell.getIntegratedDensity(imhPV) - (bgPV[0] * tomatoCell.getVolumePixels());
                                double tomatoCellIntChPNNCor = tomatoCellDonut.getIntegratedDensity(imhPNN) - (bgPNN[0] * tomatoCellDonut.getVolumePixels());


                                // Write results
                                Tomato_Analyze.write(rootName+"\t"+roiName+"\t"+sectionVol+"\t"+TomatoPop.getNbObjects()/sectionVol+"\t"+o+"\t"+tomatoCellVol+"\t"+tomatoCellIntChTomato+"\t"+
                                        bgTomato[0]+"\t"+bgTomato[1]+"\t"+tomatoCellIntChTomatoCor+"\t"+tomatoCellIntChPVCor+"\t"+tomatoCellIntChPNNCor+"\t"+pvCellIndex+"\t"+pvCellIntChPVCor+"\t"+
                                        pnnCellIndex+"\t"+ pnnCellIntChPNNCor+"\n");
                                Tomato_Analyze.flush();
                            }    
                                
                            // PNN
                            for (int o = 0; o < PNNPop.getNbObjects(); o++) {
                                Object3D obj = PNNPop.getObject(o);
                                double objVol = obj.getVolumeUnit();
                                double objIntPNN = obj.getIntegratedDensity(imhPNN);
                                // find associated pv cell
                                Object3D pvCell = tools.findAssociatedCell(PVPop, obj);
                                // find associated Tomato cell
                                Object3D TomatoCell = tools.findAssociatedCell(TomatoPop, obj);
                                double objIntPV = 0;
                                double objIntTomato = 0;
                                int pvIndex = -1;
                                int TomatoIndex = -1;
                                if (pvCell != null) {
                                    objIntPV = pvCell.getIntegratedDensity(imhPV) - (bgPV[0] * pvCell.getVolumePixels());
                                    pvIndex = PVPop.getIndexOf(pvCell);
                                }    
                                if (TomatoCell != null) {
                                    objIntTomato = TomatoCell.getIntegratedDensity(imhTomato) - bgTomato[0] * (TomatoCell.getVolumePixels());
                                    TomatoIndex = TomatoPop.getIndexOf(TomatoCell);
                                }
                                PNN_Analyze.write(rootName+"\t"+roiName+"\t"+sectionVol+"\t"+PNNPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objIntPNN+"\t"+
                                        bgPNN[0]+"\t"+bgPNN[1]+"\t"+(objIntPNN - bgPNN[0] * obj.getVolumePixels())+"\t"+pvIndex+"\t"+objIntPV+
                                        "\t"+TomatoIndex+"\t"+objIntTomato+"\n");
                                PNN_Analyze.flush();
                            }
                            tools.closeImages(imgPNN);
                            tools.closeImages(imgTomato);
                            tools.closeImages(imgPV);
                        }

                    }
                }
                if (Tomato_Analyze != null) 
                    Tomato_Analyze.close();
            
            } catch (IOException | DependencyException | ServiceException | FormatException | ParserConfigurationException | SAXException ex) {
                Logger.getLogger(IHC_PV_Tomato_PNN.class.getName()).log(Level.SEVERE, null, ex);
            }
        IJ.showStatus("Process done ...");
    }
}
