package RNA_Scope_PV;


import static Tools.RNAScope_Tools3D.closeImages;
import static Tools.RNAScope_Tools3D.findCells;
import static Tools.RNAScope_Tools3D.find_background;
import static Tools.RNAScope_Tools3D.saveRNAObjects;
import static Tools.RNAScope_Tools3D.sphericityFilterCells;
import ij.IJ;
import ij.ImagePlus;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
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
    public  static String outDirResults = "";
    public  static String rootName = "";
    public static Calibration cal = new Calibration();
    public static double minCellVol = 600;
    public static double maxCellVol = 20000;
    public static BufferedWriter RNA_PV_Analyze;

    
    
    /** initialize result files
     * 
     */
    private void writeHeaders() throws IOException {        
        // RNA results
        FileWriter  fwRNA = new FileWriter(outDirResults + "RNAScope_results.xls",false);
        RNA_PV_Analyze = new BufferedWriter(fwRNA);
        // write results headers
        RNA_PV_Analyze.write("Image Name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tIntegrated intensity\tMean background Int\t"
                + "Std background Int\tCorrected Integrated intensity\n");
        RNA_PV_Analyze.flush();
    }
    
    @Override
    public void run(String arg) {
        try {
            if (canceled) {
                IJ.showMessage(" Pluging canceled");
                return;
            }
            imageDir = IJ.getDirectory("Choose Directory Containing lif Files...");
            if (imageDir == null) {
                return;
            }
            File inDir = new File(imageDir);
            String[] imageFile = inDir.list();
            if (imageFile == null) {
                return;
            }
            // create output folder
            outDirResults = inDir + File.separator+ "Results"+ File.separator;
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
            Arrays.sort(imageFile);
            int imageNum = 0;
            for (String f : imageFile) {
                // Find lif or nd files
                String fileExt = FilenameUtils.getExtension(f);
                
                if (fileExt.equals("lif")) {
                    imageNum++;
                    rootName = FilenameUtils.getBaseName(f);
                    String imageName = inDir + File.separator+f;
                    reader.setId(imageName);
                    
                    if (imageNum == 1) {
                        // read image calibration
                        cal.pixelWidth = meta.getPixelsPhysicalSizeX(0).value().doubleValue();
                        cal.pixelHeight = cal.pixelWidth;
                        if (meta.getPixelsPhysicalSizeZ(0) != null)
                            cal.pixelDepth = meta.getPixelsPhysicalSizeZ(0).value().doubleValue();
                        else
                            cal.pixelDepth = 1;
                        cal.setUnit("microns");
                        System.out.println("x cal = " +cal.pixelWidth+", z cal=" + cal.pixelDepth);
                        // write headers
                        writeHeaders();
                    }
                    
                    ImporterOptions options = new ImporterOptions();
                    options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                    options.setId(imageName);
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
                        // section volume in mm^3
                        double sectionVol = (imgRNA.getWidth() * cal.pixelWidth * imgRNA.getHeight() * cal.pixelHeight
                                * imgRNA.getNSlices() * cal.pixelDepth) / 1e9;
                        double[] bgRNA = find_background(imgRNA);
                        Objects3DPopulation RNAPop = new Objects3DPopulation();
                        // detecte cell 
                        RNAPop = findCells(imgRNA, 9, 10, 2, "Triangle");
                        if (!seriesName.contains("Visuel"))
                            sphericityFilterCells(RNAPop);

                        System.out.println("RNA Cells found : " + RNAPop.getNbObjects());
                        ImageHandler imhRNA = ImageHandler.wrap(imgRNA);
                        for (int o = 0; o < RNAPop.getNbObjects(); o++) {
                            Object3D obj = RNAPop.getObject(o);
                            double objVol = obj.getVolumeUnit();
                            double objInt = obj.getIntegratedDensity(imhRNA);
                            if (o == 0)
                                RNA_PV_Analyze.write(rootName+"_"+seriesName+"\t"+sectionVol+"\t"+RNAPop.getNbObjects()/sectionVol+"\t"+o+"\t"+objVol+"\t"+objInt+"\t"+
                                    bgRNA[0] + "\t" + bgRNA[1] + "\t" + (objInt - (bgRNA[0] * objVol)) + "\n");
                            else 
                                RNA_PV_Analyze.write("\t\t\t"+o+"\t"+objVol+"\t"+objInt+"\t\t\t" + (objInt - (bgRNA[0] * objVol)) + "\n");
                            RNA_PV_Analyze.flush();
                        }
                        // save image for objects population
                        saveRNAObjects(RNAPop, imgRNA, outDirResults+rootName+"_"+seriesName+"_RNACells.tif");
                        closeImages(imgRNA);
                        options.setSeriesOn(s, false);
                    }
                }
            }
            RNA_PV_Analyze.close();
            IJ.showStatus("Process done ...");
        } catch (DependencyException | ServiceException | FormatException | IOException ex) {
            Logger.getLogger(mRNA_PV.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
