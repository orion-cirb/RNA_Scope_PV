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
import loci.plugins.BF;
import loci.plugins.util.ImageProcessorReader;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import java.util.ArrayList;
import java.util.List;
import loci.plugins.in.ImporterOptions;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.image3d.ImageHandler;
import org.apache.commons.io.FilenameUtils;


/*
 * Find CFOS, NeuN and PV cells
 * 
 */

/**
 *
 * @author phm
 */
public class IHC_CFOS_NeuN_PV implements PlugIn {
    
    private final boolean canceled = false;
    private String imageDir = "";
    public  String outDirResults = "";
    public  String rootName = "";
    public double sphCell = 0.5;
    public BufferedWriter Cfos_Analyze, NeuN_Analyze, PV_Analyze;

    private RNAScope_Tools3D tools = new RNAScope_Tools3D();
    
    
    /** initialize result files
     * 
     */
    private void writeHeaders() throws IOException {        

        // IHC PV results
                FileWriter fwPV = new FileWriter(outDirResults + "PV_results.xls",false);
                PV_Analyze = new BufferedWriter(fwPV);
                // write results headers
                PV_Analyze.write("Image Name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tPV Integrated intensity\tPV Mean background Int\t"
                        + "Std backgroun Int\tPV Corrected Integrated intensity\t#Cfos Cell\tCfos Corrected Integrated intensity\t#Neun Cell\tNeuN Corrected Integrated intensity\n");
                PV_Analyze.flush();

                // IHC Cfos results
                FileWriter fwCfos = new FileWriter(outDirResults + "Cfos_results.xls",false);
                Cfos_Analyze = new BufferedWriter(fwCfos);
                // write results headers
                Cfos_Analyze.write("Image Name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tCfos Integrated intensity\tCfos Mean background Int\t"
                        + "Std background Int\tCfos Corrected Integrated intensity\t#PV Cell\tPV Corrected Integrated intensity\t#NeuN Cell\tNeuN Corrected Integrated intensity\n");
                Cfos_Analyze.flush();

                 // IHC NeuN results
                FileWriter fwNeuN = new FileWriter(outDirResults + "NeuN_results.xls",false);
                NeuN_Analyze = new BufferedWriter(fwNeuN);
                // write results headers
                NeuN_Analyze.write("Image Name\tSection Volume(mm^3)\tCell density (/mm^3)\t#Cell\tCell Vol\tNeuN Integrated intensity\tNeuN Mean background Int\t"
                        + "Std background Int\tNeuN Corrected Integrated intensity\t#PV Cell\tPV Corrected Integrated intensity\t#Cfos Cell\tCfos Corrected Integrated intensity\n");
                NeuN_Analyze.flush();

    }
    
    
    
    @Override
    public void run(String arg) {
        try {
            tools.pnn = false;
            if (canceled) {
                IJ.showMessage("Pluging canceled");
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
            String[] channels = tools.findChannels(imageFile.get(0),meta, reader);
            
            // Find image calibration
            Calibration cal = tools.findImageCalib(meta);

            // write headers
            writeHeaders();
            
            // Channels dialog
            int[] channelIndex = new int[channels.length];
            List<String> channelsName = new ArrayList();
            channelsName.add("Cfos");
            channelsName.add("NeuN");
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
            for (String f : imageFile) {
                rootName = FilenameUtils.getBaseName(f);
                reader.setId(f);
                ImporterOptions options = new ImporterOptions();
                    
                        
                /** 
                 * read nd
                 * Detect IHC Cfos cells channel1, measure intensity in NeuN channel2 and PV channel4
                */

                options.setId(f);
                options.setSplitChannels(true);
                options.setQuiet(true);
                options.setCrop(true);
                options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                options.setCBegin(0, channelIndex[0]);
                options.setCEnd(0, channelIndex[0]); 

                // Cfos cells
                System.out.println("Opening Cfos channel  ...");
                ImagePlus imgCfos = BF.openImagePlus(options)[0];
                // Cfos background
                double[] bgCfos = tools.find_background(imgCfos);
                //section volume in mm^3
                double sectionVol = (imgCfos.getWidth() * cal.pixelWidth * imgCfos.getHeight() * cal.pixelHeight * imgCfos.getNSlices() * cal.pixelDepth)/1e9;
                // FindCfos cells
                Objects3DPopulation CfosPop = new Objects3DPopulation();
                if (tools.stardist)
                    CfosPop = tools.stardistCellsPop(imgCfos);
                else
                    CfosPop = tools.findCells(imgCfos, null, 18, 20, 1, "MeanPlusStDev", false, 0, 1);
                tools.filterCells(CfosPop, 0.55);
                System.out.println("Cfos Cells found : " +CfosPop.getNbObjects());

                // PV Cells
                System.out.println("Opening PV channel ...");
                options.setCBegin(0, channelIndex[2]);
                options.setCEnd(0, channelIndex[2]); 
                ImagePlus imgPV = BF.openImagePlus(options)[0];
                // PV background
                double[] bgPV = tools.find_background(imgPV);
                // find PV cells 
                Objects3DPopulation PVPop = new Objects3DPopulation();                     
                if (tools.stardist)
                    CfosPop = tools.stardistCellsPop(imgPV);
                PVPop = tools.findCells(imgPV, null, 18, 20, 1, "MeanPlusStdDev", true, 20, 1);
                System.out.println("PV Cells found : " + PVPop.getNbObjects());

                // NeuN cells
                System.out.println("Opening NeuN channel  ...");
                options.setCBegin(0, channelIndex[1]);
                options.setCEnd(0, channelIndex[1]); 
                ImagePlus imgNeuN = BF.openImagePlus(options)[0];
                // NeuN background
                double[] bgNeuN = tools.find_background(imgNeuN);
                Objects3DPopulation NeuNPop = tools.stardistCellsPop(imgNeuN);
                System.out.println("NeuN Cells found : " + NeuNPop.getNbObjects());



                // save image for objects population
                tools.saveIHCObjects(NeuNPop, PVPop, CfosPop, imgPV, outDirResults+rootName+"_IHCObjects.tif");    

                // Compute parameters

                ImageHandler imhPV = ImageHandler.wrap(imgPV);
                ImageHandler imhCfos = ImageHandler.wrap(imgCfos);
                ImageHandler imhNeuN = ImageHandler.wrap(imgNeuN);

                //Cfos
                for (int o = 0; o < CfosPop.getNbObjects(); o++) {
                    Object3D CfosCell = CfosPop.getObject(o);
                    double CfosCellVol = CfosCell.getVolumeUnit();
                    // find associated PV cell and integrated intensity
                    Object3D pvCell = tools.findAssociatedCell(PVPop, CfosCell);
                    int pvCellIndex = -1;
                    double pvCellIntChPVCor = 0;
                    if (pvCell != null) {
                        pvCellIntChPVCor = pvCell.getIntegratedDensity(imhPV) - (bgPV[0] * pvCell.getVolumePixels());
                        pvCellIndex = PVPop.getIndexOf(pvCell);
                    }
                    // find associated NeuN cell and integrated intensity
                    Object3D NeuNCell = tools.findAssociatedCell(NeuNPop, CfosCell);
                    int NeuNCellIndex = -1;
                    double NeuNCellIntChNeuNCor = 0;

                    if (NeuNCell != null) {
                        NeuNCellIntChNeuNCor = NeuNCell.getIntegratedDensity(imhNeuN) - (bgNeuN[0] * NeuNCell.getVolumePixels());
                        NeuNCellIndex = NeuNPop.getIndexOf(NeuNCell);
                    }
                    // Find Cfos integrated intensity in PV and NeuN channel
                    double CfosCellIntChCfos = CfosCell.getIntegratedDensity(imhCfos);
                    double CfosCellIntChCfosCor = CfosCellIntChCfos - (bgCfos[0] * CfosCell.getVolumePixels());
                    double CfosCellIntChPVCor = CfosCell.getIntegratedDensity(imhPV) - (bgPV[0] * CfosCell.getVolumePixels());
                    double CfosCellIntChNeuNCor = CfosCell.getIntegratedDensity(imhNeuN) - (bgNeuN[0] * CfosCell.getVolumePixels());

 
                    // Write results
                   Cfos_Analyze.write(rootName+"\t"+sectionVol+"\t"+CfosPop.getNbObjects()/sectionVol+"\t"+o+"\t"+CfosCellVol+"\t"+CfosCellIntChCfos+"\t"+
                            bgCfos[0]+"\t"+bgCfos[1]+"\t"+CfosCellIntChCfosCor+"\t"+pvCellIndex+"\t"+pvCellIntChPVCor+"\t"+NeuNCellIndex+"\t"+NeuNCellIntChNeuNCor+"\n");
                   Cfos_Analyze.flush();
                }

                // PV
              
                for (int o = 0; o < PVPop.getNbObjects(); o++) {
                    Object3D PVCell = PVPop.getObject(o);
                    double PVCellVol = PVCell.getVolumeUnit();
                    double PVCellIntPV = PVCell.getIntegratedDensity(imhPV);
                    double PVCellIntCfos = PVCell.getIntegratedDensity(imhCfos);
                    double PVCellIntPVCor = PVCellIntPV - (bgPV[0] * PVCell.getVolumePixels());
                    // find associated Cfos cell and integrated intensity 
                    Object3D CfosCell = tools.findAssociatedCell(CfosPop, PVCell);
                    int CfosCellIndex = -1;
                    double CfosCellIntChCfos = 0;
                    double CfosCellIntChCfosCor = 0;
                    if (CfosCell != null) {
                        CfosCellIntChCfosCor = CfosCell.getIntegratedDensity(imhCfos) - (bgCfos[0] * CfosCell.getVolumePixels());
                        CfosCellIndex = CfosPop.getIndexOf(PVCell);
                    }
                    // find associated NeuN cell and integrated intensity
                    Object3D NeuNCell = tools.findAssociatedCell(NeuNPop, PVCell);
                    int NeuNCellIndex = -1;
                    double NeuNCellIntChNeuNCor = 0;

                    if (NeuNCell != null) {
                        NeuNCellIntChNeuNCor = NeuNCell.getIntegratedDensity(imhNeuN) - (bgNeuN[0] * NeuNCell.getVolumePixels());
                        NeuNCellIndex = NeuNPop.getIndexOf(NeuNCell);
                    }
                    PV_Analyze.write(rootName+"\t"+sectionVol+"\t"+PVPop.getNbObjects()/sectionVol+"\t"+o+"\t"+PVCellVol+"\t"+PVCellIntPV+"\t"+
                            bgPV[0]+"\t"+ bgPV[1] + "\t" + PVCellIntPVCor+"\t"+NeuNCellIndex+"\t"+NeuNCellIntChNeuNCor+"\t"+CfosCellIndex+"\t"+CfosCellIntChCfosCor+"\n");
                    PV_Analyze.flush();
                }
                
                //NeuN
                for (int o = 0; o < NeuNPop.getNbObjects(); o++) {
                    Object3D NeuNCell = NeuNPop.getObject(o);
                    double NeuNCellVol = NeuNCell.getVolumeUnit();
                    // find associated PV cell and integrated intensity
                    Object3D pvCell = tools.findAssociatedCell(PVPop, NeuNCell);
                    int pvCellIndex = -1;
                    double pvCellIntChPVCor = 0;
                    if (pvCell != null) {
                        pvCellIntChPVCor = pvCell.getIntegratedDensity(imhPV) - (bgPV[0] * pvCell.getVolumePixels());
                        pvCellIndex = PVPop.getIndexOf(pvCell);
                    }
                    // find associated Cfos cell and integrated intensity
                    Object3D CfosCell = tools.findAssociatedCell(CfosPop, NeuNCell);
                    int CfosCellIndex = -1;
                    double CfosCellIntChCfosCor = 0;

                    if (CfosCell != null) {
                        CfosCellIntChCfosCor = CfosCell.getIntegratedDensity(imhCfos) - (bgCfos[0] * CfosCell.getVolumePixels());
                        CfosCellIndex = CfosPop.getIndexOf(CfosCell);
                    }
                    // Find NeuN integrated intensity in PV and Cfos channel
                    double NeuNCellIntChNeuN = NeuNCell.getIntegratedDensity(imhNeuN);
                    double NeuNCellIntChNeuNCor = NeuNCellIntChNeuN - (bgNeuN[0] * NeuNCell.getVolumePixels());
                    double NeuNCellIntChPVCor = NeuNCell.getIntegratedDensity(imhPV) - (bgPV[0] * NeuNCell.getVolumePixels());
                    double NeuNCellIntChCfosCor = NeuNCell.getIntegratedDensity(imhCfos) - (bgCfos[0] * NeuNCell.getVolumePixels());

 
                    // Write results
                   NeuN_Analyze.write(rootName+"\t"+sectionVol+"\t"+CfosPop.getNbObjects()/sectionVol+"\t"+o+"\t"+NeuNCellVol+"\t"+NeuNCellIntChNeuN+"\t"+
                            bgNeuN[0]+"\t"+bgNeuN[1]+"\t"+NeuNCellIntChNeuNCor+"\t"+pvCellIndex+"\t"+pvCellIntChPVCor+"\t"+CfosCellIndex+"\t"+CfosCellIntChCfosCor+"\n");
                   NeuN_Analyze.flush();
                }
                        
                tools.closeImages(imgCfos);
                tools.closeImages(imgCfos);
                tools.closeImages(imgPV);

            }
            if (Cfos_Analyze != null) 
               Cfos_Analyze.close();

        } catch (IOException | DependencyException | ServiceException | FormatException  ex) {
            Logger.getLogger(IHC_CFOS_NeuN_PV.class.getName()).log(Level.SEVERE, null, ex);
        }
        IJ.showStatus("Process done ...");
    }
}
