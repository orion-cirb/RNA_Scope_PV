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
import java.util.ArrayList;
import loci.plugins.in.ImporterOptions;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.image3d.ImageHandler;
import org.apache.commons.io.FilenameUtils;


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
public class mRNA_PV implements PlugIn {
    
    private final boolean canceled = false;
    private String imageDir = "";
    public  String outDirResults = "";
    public  String rootName = "";
    public BufferedWriter RNA_PV_Analyze;

    private RNAScope_Tools3D tools = new RNAScope_Tools3D();
    
    /** initialize result files
     * 
     */
    private void writeHeaders() throws IOException {        
        // RNA results
        FileWriter  fwRNA = new FileWriter(outDirResults + "RNAScope_results.xls",false);
        RNA_PV_Analyze = new BufferedWriter(fwRNA);
        // write results headers
        RNA_PV_Analyze.write("Image Name\tLayer name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tIntegrated intensity\tMean background Int\t"
                + "Std background Int\tCorrected Integrated intensity\n");
        RNA_PV_Analyze.flush();
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
            
            // write headers
            writeHeaders();

            // Find image calibration
            Calibration cal = tools.findImageCalib(meta);

            for (String f : imageFile) {
                rootName = FilenameUtils.getBaseName(f);

                // find if roi file exist
                RoiManager rm = new RoiManager(false);
                String roiFileName = imageDir + File.separator + rootName + ".zip";

                if (!new File(roiFileName).exists()) {
                    IJ.showStatus("No roi file found ...");
                }
                else {
                    rm.runCommand("Open", roiFileName);
                    ImporterOptions options = new ImporterOptions();
                    options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                    options.setId(f);
                    options.setSplitChannels(true);

                    // for all series
                    int series = reader.getSeriesCount();
                    for (int s = 0; s < series; s++) {
                        reader.setSeries(s);
                        options.setSeriesOn(s, true);
                        options.setCBegin(s, 0);
                        options.setCEnd(s, 0);
                        String seriesName = meta.getImageName(s);

                        //Open RNA channel (1)
                        //Detect RNA PV cells and measure intensity

                        System.out.println("-- Opening RNA channel " + seriesName);
                        ImagePlus imgRNA = BF.openImagePlus(options)[0];
                        // Add roi if name contain seriesName crop image
                        ArrayList<Roi> rois = tools.findRoi(rm, seriesName);
                        for (Roi roi : rois) {
                            String roiName = roi.getName();
                            String layerName = roiName.replace(seriesName, "");
                            // section volume in mm^3
                            double sectionVol = (imgRNA.getWidth() * cal.pixelWidth * imgRNA.getHeight() * cal.pixelHeight
                                    * imgRNA.getNSlices() * cal.pixelDepth) / 1e9;
                            double[] bgRNA = tools.find_background(imgRNA);
                            Objects3DPopulation RNAPop = new Objects3DPopulation();
                            if (seriesName.contains("Visuel"))
                                if (tools.stardist)
                                   RNAPop = tools.stardistCellsPop(imgRNA);
                                else
                                    RNAPop = tools.findCells(imgRNA, roi, 9, 10, 2, "Triangle", false, 0, 1);
                            else
                                RNAPop = tools.findCellsPiriform(imgRNA, roi, 10, 12, 1.5, "RenyiEntropy");
                            tools.filterCells(RNAPop, 0.45);
                            System.out.println("RNA Cells found : " + RNAPop.getNbObjects());
                            ImageHandler imhRNA = ImageHandler.wrap(imgRNA);
                            for (int o = 0; o < RNAPop.getNbObjects(); o++) {
                                Object3D obj = RNAPop.getObject(o);
                                double objVol = obj.getVolumeUnit();
                                double objInt = obj.getIntegratedDensity(imhRNA);
                                if (o == 0)
                                    RNA_PV_Analyze.write(rootName+"_"+seriesName+"\t"+layerName+"\t"+sectionVol+"\t"+RNAPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objInt+"\t"+
                                        bgRNA[0] + "\t" + bgRNA[1] + "\t" + (objInt - (bgRNA[0] * obj.getVolumePixels())) + "\n");
                                else 
                                    RNA_PV_Analyze.write("\t\t\t\t"+o+"\t"+objVol+"\t"+objInt+"\t\t\t" + (objInt - (bgRNA[0] * obj.getVolumePixels())) + "\n");
                                RNA_PV_Analyze.flush();
                            }
                            // save image for objects population
                            tools.saveRNAObjects(RNAPop, imgRNA, outDirResults+rootName+"_"+seriesName+"-"+layerName+"_RNACells.tif");
                        }
                        tools.closeImages(imgRNA);
                        options.setSeriesOn(s, false);
                    }
                }
            }
            if (RNA_PV_Analyze != null)
                RNA_PV_Analyze.close();
            IJ.showStatus("Process done ...");
        } catch (DependencyException | ServiceException | FormatException | IOException ex) {
            Logger.getLogger(mRNA_PV.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
