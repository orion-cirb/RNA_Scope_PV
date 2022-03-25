package RNA_Scope_PV;


import ij.IJ;
import ij.ImagePlus;
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
import loci.plugins.util.ImageProcessorReader;
import loci.plugins.BF;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import java.util.ArrayList;
import javax.xml.parsers.ParserConfigurationException;
import loci.plugins.in.ImporterOptions;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.geom.Point3D;
import mcib3d.image3d.ImageHandler;
import org.apache.commons.io.FilenameUtils;
import org.xml.sax.SAXException;


/*
 * Find nucleus with reference gene and/or virus 
 * based on objects segmentation of gene and virus channels
 * find mRNA in nucleus+ population
 * 
 */

/**
 *
 * @author phm
 */
public class mRNA_Scope_PV implements PlugIn {
    
    private final boolean canceled = false;
    private String imageDir = "";
    public  String outDirResults = "";
    public  String rootName = "";
    // threshold to keep PV and Tomato cells
    public double PVMinInt, TomatoMinInt;
    public double sphCell = 0.5;

    private RNAScope_Tools3D tools = new RNAScope_Tools3D();
           
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
            outDirResults = imageDir + File.separator+ "Out"+ File.separator;
            File outDir = new File(outDirResults);
            if (!Files.exists(Paths.get(outDirResults))) {
                outDir.mkdir();
            }

            // initialize result files
            
            // RNA results
            FileWriter  fwRNA = new FileWriter(outDirResults + "RNAScope_results.xls",false);
            BufferedWriter RNAScope_Analyze = new BufferedWriter(fwRNA);
            // write results headers
            RNAScope_Analyze.write("Image Name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tIntegrated intensity\tMean background Int\t"
                    + "Std background Int\tCorrected Integrated intensity\n");
            RNAScope_Analyze.flush();
            
            // IHC PV results
            FileWriter fwPV = new FileWriter(outDirResults + "PV_results.xls",false);
            BufferedWriter PV_Analyze = new BufferedWriter(fwPV);
            // write results headers
            PV_Analyze.write("Image Name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tPV Mean Intensity\tPV Integrated intensity\tPV Mean background Int\t"
                    + "Std backgroun Int\tPV Corrected Integrated intensity\tTomato Corrected Integrated intensity\tPNN Corrected Integrated intensity\n");
            PV_Analyze.flush();
            
            // IHC Tomato results
            FileWriter fwTomato = new FileWriter(outDirResults + "Tomato_results.xls",false);
            BufferedWriter Tomato_Analyze = new BufferedWriter(fwTomato);
            // write results headers
            Tomato_Analyze.write("Image Name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tTomato Integrated intensity\tTomato Mean background Int\t"
                    + "Std background Int\tTomato Corrected Integrated intensity\tPV Corrected Integrated intensity\tPNN Corrected Integrated intensity\n");
            Tomato_Analyze.flush();
            
             // IHC PNN results
            FileWriter fwPNN = new FileWriter(outDirResults + "PNN_results.xls",false);
            BufferedWriter PNN_Analyze = new BufferedWriter(fwPNN);
            // write results headers
            PNN_Analyze.write("Image Name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tPNN Integrated intensity\tPNN Mean background Int\t"
                    + "Std background Int\tPNN Corrected Integrated intensity\t#PV Cell\tPV Corrected Integrated intensity\t#Tomato Cell\tTomato Corrected Integrated intensity\n");
            PNN_Analyze.flush();
            
            // create OME-XML metadata store of the latest schema version
            ServiceFactory factory;
            factory = new ServiceFactory();
            OMEXMLService service = factory.getInstance(OMEXMLService.class);
            IMetadata meta = service.createOMEXMLMetadata();
            ImageProcessorReader reader = new ImageProcessorReader();
            reader.setMetadataStore(meta);
            reader.setId(imageFile.get(0));
            
            // Find image calibration
            Calibration cal = tools.findImageCalib(meta);

            for (String f : imageFile) {
                rootName = FilenameUtils.getBaseName(f);
                reader.setId(f);
                ImporterOptions options = new ImporterOptions();
                int series = reader.getSeriesCount();
                   
                options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                options.setId(f);
                options.setSplitChannels(true);


                // for all series
                for (int s = 0; s < series; s++) {
                    String seriesName = meta.getImageName(s);
                    ImagePlus imgRNA;
                    Objects3DPopulation RNAPop = new Objects3DPopulation();
                    Objects3DPopulation PVPop = new Objects3DPopulation();
                    Objects3DPopulation PVDonutPop = new Objects3DPopulation();
                    Objects3DPopulation TomatoPop = new Objects3DPopulation();
                    Objects3DPopulation TomatoDonutPop = new Objects3DPopulation();
                    Objects3DPopulation PNNPop = new Objects3DPopulation(); 


                    /** 
                     * Take only series with RNAscope and condition 1
                     * Detect RNA PV cells and measure intensity
                    */
                    if (seriesName.contains("RNAscope") && seriesName.contains("condition 1")) {
                       //Open RNA channel (1)
                       System.out.println("-- "+seriesName);
                       options.setSeriesOn(s, true);
                       options.setCBegin(s, 1);
                       options.setCEnd(s, 1);
                       reader.setSeries(s);
                       int sizeZ = reader.getSizeZ();
                       cal = tools.findImageCalib(meta);
                       
                       System.out.println("Open RNA Channel");
                       imgRNA = BF.openImagePlus(options)[0];
                       // section volume in mm^3
                       double sectionVol = (imgRNA.getWidth() * cal.pixelWidth * imgRNA.getHeight() * cal.pixelHeight
                               * sizeZ * cal.pixelDepth) / Math.pow(10, 9);
                       double[] bgRNA = tools.find_background(imgRNA);
                       RNAPop = new Objects3DPopulation();
                       if (tools.stardist)
                           RNAPop = tools.stardistCellsPop(imgRNA);
                       else
                           RNAPop = tools.findCells(imgRNA, null, 8, 10, 1, "Li", false, 0, 1);
                       System.out.println("RNA Cells found : " + RNAPop.getNbObjects());
                       ImageHandler imhRNA = ImageHandler.wrap(imgRNA);
                       for (int o = 0; o < RNAPop.getNbObjects(); o++) {
                            Object3D obj = RNAPop.getObject(o);
                            double objVol = obj.getVolumeUnit();
                            double objInt = obj.getIntegratedDensity(imhRNA);
                            RNAScope_Analyze.write(rootName+"_"+seriesName+"\t"+sectionVol+"\t"+RNAPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objInt+"\t"+
                                    bgRNA[0] + "\t" + bgRNA[1] + "\t" + (objInt - (bgRNA[0] * obj.getVolumePixels())) + "\n");
                            RNAScope_Analyze.flush();
                       }
                       options.setSeriesOn(s, false);
                       // save image for objects population
                        tools.saveRNAObjects(RNAPop, imgRNA, outDirResults+rootName+"_"+seriesName+"_RNACells.tif");
                        tools.closeImages(imgRNA);
                    }
                    /** 
                     * Take only series with seriée and condition 1
                     * Detect IHC PV cells, measure intensity in PV channel2 and Tomato channel1
                     * compute donut PV Object and measure in PNN channel0
                     * Detect Tomato cells measure intensity in Tomato and PV channels
                     * Detect PNN cells measure intensity in PNN channel and find corresponding PV Cell
                    */
                    else if (seriesName.contains("sériée") && seriesName.contains("condition 1") ) {
                        // Open PV, Tomato, PNN channels
                        System.out.println("-- "+seriesName);
                        reader.setSeries(s);
                       int sizeZ = reader.getSizeZ();
                       cal = tools.findImageCalib(meta);
                       // Find xml points file
                        String xmlFile = imageDir+ File.separator + rootName + " - " + seriesName + ".xml";
                        if (!new File(xmlFile).exists()) {
                            IJ.showStatus("No XML file found !") ;
                        }
                        else {
                            options.setSeriesOn(s, true);
                            options.setCBegin(s, 0);
                            options.setCEnd(s, 2);
                            ImagePlus imgPV = BF.openImagePlus(options)[2];
                            ImagePlus imgTomato = BF.openImagePlus(options)[1];
                            ImagePlus imgPNN = BF.openImagePlus(options)[0];
                            //section volume in mm^3
                            double sectionVol = (imgPV.getWidth() * cal.pixelWidth * imgPV.getHeight() * cal.pixelHeight * sizeZ * cal.pixelDepth)/1e9;

                            // Find background
                            System.out.println("PV");
                            double[] bgPV = tools.find_background(imgPV);
                            System.out.println("Tomato");
                            double[] bgTomato = tools.find_background(imgTomato);
                            System.out.println("PNN");
                            double[] bgPNN = tools.find_background(imgPNN);

                            /** 
                             * Find PV, Tomato and PNN objects
                             */

                            float dilatedStepXY = (float) (6/cal.pixelWidth);
                            float dilatedStepZ = (float) (6/cal.pixelDepth);

                            // PV
                            PVPop = tools.findCells(imgPV, null, 8, 10, 1, "MeanPlusStdDev", false, 0, 1);
                            System.out.println("PV Cells found : " + PVPop.getNbObjects());

                            // filter again sphericity and intensity
                            //filtersPVcells(PVPop, bgPV, imgPV);
                            System.out.println("PV Cells found after filter: " + PVPop.getNbObjects());

                            PVDonutPop  = tools.createDonutPop(PVPop, imgPV, dilatedStepXY, dilatedStepZ);
                            ImageHandler imhPV = ImageHandler.wrap(imgPV);
                            ImageHandler imhTomato = ImageHandler.wrap(imgTomato);
                            ImageHandler imhPNN = ImageHandler.wrap(imgPNN);
                            for (int o = 0; o < PVPop.getNbObjects(); o++) {
                                Object3D obj = PVPop.getObject(o);
                                Object3D objDonut = PVDonutPop.getObject(o);
                                double objVol = obj.getVolumeUnit();
                                double objIntPV = obj.getIntegratedDensity(imhPV);
                                double objMeanPV = obj.getPixMeanValue(imhPV);
                                double objIntTomato = obj.getIntegratedDensity(imhTomato);
                                double objIntPNN = objDonut.getIntegratedDensity(imhPNN);
                                PV_Analyze.write(rootName+"_"+seriesName+"\t"+sectionVol+"\t"+PVPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objMeanPV+"\t"+objIntPV+"\t"+
                                        bgPV[0]+"\t"+ bgPV[1] + "\t" + (objIntPV - (bgPV[0] * obj.getVolumePixels()))+"\t"+(objIntTomato - (bgTomato[0] * objVol))+"\t"+
                                        (objIntPNN - (bgPNN[0] * objDonut.getVolumePixels()))+"\n");
                                PV_Analyze.flush();
                            }
                            // Tomato
                            TomatoPop = tools.findCells(imgTomato, null, 8, 10, 1, "Yen", false, 0, 1);
                            System.out.println("Tomato Cells found : " + TomatoPop.getNbObjects());
                            TomatoDonutPop  = tools.createDonutPop(TomatoPop, imgTomato, dilatedStepXY, dilatedStepZ);
                            for (int o = 0; o < TomatoPop.getNbObjects(); o++) {
                                Object3D obj = TomatoPop.getObject(o);
                                Object3D objDonut = TomatoDonutPop.getObject(o);
                                double objVol = obj.getVolumeUnit();
                                double objIntPV = obj.getIntegratedDensity(imhPV);
                                double objIntTomato = obj.getIntegratedDensity(imhTomato);
                                double objIntPNN = objDonut.getIntegratedDensity(imhPNN);
                                Tomato_Analyze.write(rootName+"_"+seriesName+"\t"+sectionVol+"\t"+TomatoPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objIntTomato+"\t"+
                                        bgTomato[0]+"\t"+bgTomato[1]+"\t"+(objIntTomato - (bgTomato[0] * obj.getVolumePixels()))+"\t"+(objIntPV - (bgPV[0] * obj.getVolumePixels()))+"\t"+
                                        (objIntPNN - (bgPNN[0] * objDonut.getVolumePixels()))+"\n");
                                Tomato_Analyze.flush();
                            }
                            // Find PNN cells with xml points file
                            ArrayList<Point3D> PNNPoints = tools.readXML(xmlFile, null);
                            if (tools.pnnDetection.equals("Ridge"))
                                    PNNPop = tools.findPNNCellsRidge(imgPNN, null, PNNPoints);
                            else
                                PNNPop = tools.findPNNCellsOutliner(imgPNN, null, PNNPoints);

                            System.out.println("PNN Cells found : " + PNNPop.getNbObjects());
                            for (int o = 0; o < PNNPop.getNbObjects(); o++) {
                                Object3D obj = PNNPop.getObject(o);
                                double objVol = obj.getVolumeUnit();
                                double objIntPNN = obj.getIntegratedDensity(imhPNN);
                                // find associated pv cell
                                Object3D pvCell = tools.findAssociatedCell(PVPop, obj);
                                // find associated tomato cell
                                Object3D tomatoCell = tools.findAssociatedCell(TomatoPop, obj);
                                double objIntPV = 0;
                                double objIntTomato = 0;
                                int pvIndex = -1;
                                int tomatoIndex = -1;
                                if (pvCell != null) {
                                    objIntPV = pvCell.getIntegratedDensity(imhPV) - (bgPV[0] * pvCell.getVolumePixels());
                                    pvIndex = PVPop.getIndexOf(pvCell);
                                }    
                                if (tomatoCell != null) {
                                    objIntTomato = tomatoCell.getIntegratedDensity(imhTomato) - bgTomato[0] * (tomatoCell.getVolumePixels());
                                    tomatoIndex = TomatoPop.getIndexOf(tomatoCell);
                                }
                                PNN_Analyze.write(rootName+"_"+seriesName+"\t"+sectionVol+"\t"+PNNPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objIntPNN+"\t"+
                                        bgPNN[0]+"\t"+bgPNN[1]+"\t"+(objIntPNN - bgPNN[0] * obj.getVolumePixels())+"\t"+pvIndex+"\t"+objIntPV+
                                        "\t"+tomatoIndex+"\t"+objIntTomato+"\n");
                                PNN_Analyze.flush();
                            }
                            // save image for objects population
                            tools.saveIHCObjects(PVPop, TomatoPop, PNNPop, imgPV, outDirResults+rootName+"_"+seriesName+"_IHCObjects.tif");
                            options.setSeriesOn(s, false);
                            tools.closeImages(imgPV);
                            tools.closeImages(imgTomato);
                            tools.closeImages(imgPNN);
                        }
                    }
                }
            }
            if (RNAScope_Analyze != null) {
                RNAScope_Analyze.close();
                PV_Analyze.close();
                Tomato_Analyze.close();
                PNN_Analyze.close();
            }
        } catch (IOException | DependencyException | ServiceException | FormatException | ParserConfigurationException | SAXException ex) {
            Logger.getLogger(mRNA_Scope_PV.class.getName()).log(Level.SEVERE, null, ex);
        }
        IJ.showStatus("Process done ...");
    }
}

