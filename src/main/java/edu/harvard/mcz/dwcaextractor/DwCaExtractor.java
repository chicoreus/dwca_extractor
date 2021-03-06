/**
 * DwCaExtractor.java
 * 
 * Modified from FP-Akka DwCaReader.java 
 * 
 * Copyright 2017 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.harvard.mcz.dwcaextractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.gbif.dwc.record.StarRecord;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.Term;
import org.gbif.dwc.text.Archive;
import org.gbif.dwc.text.ArchiveFactory;
import org.gbif.dwc.text.UnsupportedArchiveException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Read occurrence data from DarwinCore Archive files, write out as flat darwin core csv.
 * 
 * Optionally duplicate records as example data.
 * 
 * @author mole
 *
 */
public class DwCaExtractor {
	
	private static final Log logger = LogFactory.getLog(DwCaExtractor.class);

    @Option(name="-i",usage="input Darwin Core Archive file",required=true,aliases="--input")
    private String archiveFilePath = "dwca.zip";
    
    @Option(name="-o",usage="output csv file",aliases="--output")
    private String outputFilename = "output.csv";
    
    @Option(name="-a",usage="append to the output csv file instead of overwriting",aliases="--append")
    private boolean append = false;
    
    @Option(name="-l",usage="maximum number of records to read",aliases="--limit",forbids="-c")
    private int recordLimit = 0; // maximum records to read, zero or less for no limit.
    
    @Option(name="-c",usage="Cherry pick only particular occurrence records (pipe delimited list of occurrenceIds) ",aliases="--cherry-pick")
    private String targetOccurrenceIDs = "";
    
    @Option(name="-d",usage="DOI for the source dataset",aliases="--doi")
    private String doi = "";
    
    @Option(name="-e",usage="Duplicate records into example records with newly minted uuid occurrenceId ",aliases="--create-example-copies")
    private boolean createExamples = false;
    
    @Option(name="-h",usage="Display this help message. ",aliases="--help")
    private boolean help = false;
    
    ArrayList<String> targetOccIDList = null;
    
    private int extractedRecords = 0;  // number of records read.

    public Archive dwcArchive = null;
    public CSVPrinter csvPrinter = null;
    public Reader inputReader = null;
    public String recordClass = null;
    public String[] headers = new String[]{};

    //private int reportSize = 1000;
    
    Iterator<StarRecord> iterator;

    long start;	    

    public static void main(String[] args) {

    	int exitState = 0;
    	
    	DwCaExtractor extractor = new DwCaExtractor();
    	if (extractor.setup(args)) { 
    		try {
    			extractor.extract();
    		} catch (IOException e) {
    			logger.error(e.getMessage(),e);
    			exitState = 2;
    		}
    	} else { 
    		logger.error("Failed to setup for extraction.");
    		exitState = 1;
    	}
    	
    	System.exit(exitState);
    } 

    public DwCaExtractor() {
    }

    /**
     * Setup conditions to run.
     * 
     * @param args command line arguments
     * @return true if setup was successful, false otherwise.
     */
    protected boolean setup(String[] args) {
    	boolean setupOK = false;
    	CmdLineParser parser = new CmdLineParser(this);
    	//parser.setUsageWidth(4096);
    	try {
    		parser.parseArgument(args);
    		
    		if (help) { 
    			parser.printUsage(System.out);
    			System.exit(0);
    		}

    		if (archiveFilePath != null) { 
    			String filePath = archiveFilePath;
    			logger.debug(filePath);
    			File file =  new File(filePath);
    			if (!file.exists()) { 
    				// Error
    				logger.error(filePath + " not found.");
    			}
    			if (!file.canRead()) { 
    				// error
    				logger.error("Unable to read " + filePath);
    			}
    			if (file.isDirectory()) { 
    				// check if it is an unzipped dwc archive.
    				dwcArchive = openArchive(file);
    			}
    			if (file.isFile()) { 
    				// unzip it
    				File outputDirectory = new File(file.getName().replace(".", "_") + "_content");
    				if (!outputDirectory.exists()) {
    					outputDirectory.mkdir();
    					try {
    						byte[] buffer = new byte[1024];
    						ZipInputStream inzip = new ZipInputStream(new FileInputStream(file));
    						ZipEntry entry =  inzip.getNextEntry();
    						while (entry!=null) { 
    							String fileName = entry.getName();
    							File expandedFile = new File(outputDirectory.getPath() + File.separator + fileName);
    							new File(expandedFile.getParent()).mkdirs();
    							FileOutputStream expandedfileOutputStream = new FileOutputStream(expandedFile);             
    							int len;
    							while ((len = inzip.read(buffer)) > 0) {
    								expandedfileOutputStream.write(buffer, 0, len);
    							}

    							expandedfileOutputStream.close();   
    							entry = inzip.getNextEntry();							
    						}
    						inzip.closeEntry();
    						inzip.close();
    						logger.debug("Unzipped archive into " + outputDirectory.getPath());
    					} catch (FileNotFoundException e) {
    						logger.error(e.getMessage());
    					} catch (IOException e) {
    						logger.error(e.getMessage(),e);
    					}
    				}
    				// look into the unzipped directory
    				dwcArchive = openArchive(outputDirectory);
    			}
    			if (dwcArchive!=null) { 
    				if (checkArchive()) {
    					// Check output 
    					csvPrinter = new CSVPrinter(new FileWriter(outputFilename, append), CSVFormat.DEFAULT.withQuoteMode(QuoteMode.NON_NUMERIC));
    					// no exception thrown
    					setupOK = true;
    				}
    			} else { 
    				System.out.println("Problem opening archive.");
    				logger.error("Unable to unpack archive file.");
    			}
    			logger.debug(setupOK);
    		}

    	} catch( CmdLineException e ) {
    		logger.error(e.getMessage());
    		parser.printUsage(System.err);
    	} catch (IOException e) {
    		logger.error(e.getMessage());
    		System.out.println(e.getMessage());
    		parser.printUsage(System.err);
    	}
    	return setupOK;
    }


    /**
     * Attempt to open a DarwinCore archive directory and return it as an Archive object.  
     * If an UnsupportedArchiveException is thrown, trys again harder by looking for an archive
     * directory inside the provided directory.
     * 
     * @param outputDirectory directory that should represent an unzipped DarwinCore archive.
     * @return an Archive object repsesenting the content of the directory or null if unable
     * to open an archive object.
     */
    protected Archive openArchive(File outputDirectory) { 
    	Archive result = null;
    	try {
    		result = ArchiveFactory.openArchive(outputDirectory);
    	} catch (UnsupportedArchiveException e) {
    		logger.error(e.getMessage());
    		File[] containedFiles = outputDirectory.listFiles();
    		boolean foundContained = false;
    		for (int i = 0; i<containedFiles.length; i++) { 
    			if (containedFiles[i].isDirectory()) {
    				try {
    					// Try harder, some pathological archives contain a extra level of subdirectory
    					result = ArchiveFactory.openArchive(containedFiles[i]);
    					foundContained = true;
    				} catch (Exception e1) { 
    					logger.error(e.getMessage());
    					System.out.println("Unable to open archive directory " + e.getMessage());
    					System.out.println("Unable to open directory contained within archive directory " + e1.getMessage());
    				}
    			}
    		}
    		if (!foundContained) { 
    			System.out.println("Unable to open archive directory " + e.getMessage());
    		}					
    	} catch (IOException e) {
    		logger.error(e.getMessage());
    		System.out.println("Unable to open archive directory " + e.getMessage());
    	}
    	return result;
    }

    protected boolean checkArchive() {
    	boolean result = false;
    	if (dwcArchive==null) { 
    		return result;
    	}
    	if (dwcArchive.getCore() == null) {
    		System.out.println("Cannot locate the core datafile in " + dwcArchive.getLocation().getPath());
    		return result;
    	}
    	logger.debug("Core file found: " + dwcArchive.getCore().getLocations());
    	logger.debug("Core row type: " + dwcArchive.getCore().getRowType());
    	logger.debug(dwcArchive.getCore().getRowType().simpleName());
    	if (dwcArchive.getCore().getRowType().equals(DwcTerm.Occurrence) ) {

    		// check expectations 
    		List<DwcTerm> expectedTerms = new ArrayList<DwcTerm>();
    		expectedTerms.add(DwcTerm.scientificName);
    		expectedTerms.add(DwcTerm.scientificNameAuthorship);
    		expectedTerms.add(DwcTerm.eventDate);
    		expectedTerms.add(DwcTerm.recordedBy);
    		expectedTerms.add(DwcTerm.decimalLatitude);
    		expectedTerms.add(DwcTerm.decimalLongitude);
    		expectedTerms.add(DwcTerm.locality);
    		expectedTerms.add(DwcTerm.basisOfRecord);
    		expectedTerms.add(DwcTerm.occurrenceID);

    		for (DwcTerm term : expectedTerms) {
    			if (!dwcArchive.getCore().hasTerm(term)) {
    				logger.debug("Cannot find " + term + " in core of input dataset.");
    			}
    		} 		

    		result = true;
    	} else {
    		logger.error("Darwin Core Archive does not have an Occurrence core.");
    		// currently can only process occurrence core
    	}

    	return result;
    }

    /**
     * Assuming setup succeeded, extract data from input file as indicated by command line parameters.
     * 
     * @throws IOException
     */
    protected void extract() throws IOException {
    	
    	Gson gson = new Gson();

    	List<String> targets = getTargetOccIDList();
    	Iterator<String> ti = targets.iterator();
    	while (ti.hasNext()) { 
    	    logger.debug(ti.next());
    	}

    	// obtain the list of flat darwin core terms, excluding resource relationship and measurement or fact.
    	List<DwcTerm> flatTerms = DwcTerm.listByGroup(DwcTerm.GROUP_RECORD);
    	flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_OCCURRENCE));
    	flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_IDENTIFICATION));
    	flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_LOCATION));
    	flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_EVENT));
    	flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_GEOLOGICALCONTEXT));
    	flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_TAXON));
    	flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_MATERIAL_SAMPLE));
    	flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_ORGANISM));
    	flatTerms.remove(DwcTerm.Occurrence);
    	flatTerms.remove(DwcTerm.Identification);
    	flatTerms.remove(DwcTerm.Event);
    	flatTerms.remove(DwcTerm.GeologicalContext);
    	flatTerms.remove(DwcTerm.Taxon);
    	flatTerms.remove(DwcTerm.MaterialSample);
    	flatTerms.remove(DwcTerm.Organism);

    	if (!append) { 
    		// Write out header 
    		Iterator<DwcTerm> ih = flatTerms.iterator();
    		while (ih.hasNext()) { 
    			DwcTerm term = ih.next();
    			if (!term.isClass()) {
    				// write the simple name of the term as a column header
    			   String name = term.simpleName();
    		  	   csvPrinter.print(name);
    			} else { 
    				logger.error(term.simpleName());
    			}
    		}
    		if (createExamples) { 
    			// add additional terms to tie examples back to the sources they are copied from.
    			csvPrinter.print(DwcTerm.relatedResourceID);
    			csvPrinter.print(DwcTerm.relationshipOfResource);
    			csvPrinter.print(DwcTerm.relationshipRemarks);
    		}

    		csvPrinter.println();
    		csvPrinter.flush();
    	} 

    	Iterator<StarRecord> iterator = dwcArchive.iterator();
    	Set<Term> terms = dwcArchive.getCore().getTerms();
    	Iterator<Term> i = terms.iterator();
    	while (i.hasNext()) { 
    		logger.info(i.next().simpleName());
    	}
    	while (iterator.hasNext() && (extractedRecords < recordLimit || recordLimit==0)) {
    		// read data, output selected record.
    		StarRecord dwcrecord = iterator.next();

    		String occID = dwcrecord.core().value(DwcTerm.occurrenceID);
    		logger.debug(occID);

    		if (targets.size()==0 || targets.contains(occID)) { 

    			Iterator<DwcTerm> it = flatTerms.iterator();
    			String sourceOccurrenceID = null;
    			while (it.hasNext()) {
    				DwcTerm term = it.next();
    				String key = term.simpleName();
    				String value = dwcrecord.core().value(term);
    				if (key.equals(DwcTerm.occurrenceID.simpleName())) { 
    					// store a copy of the original occurrenceID
    					sourceOccurrenceID = value;
    				}
    				// if a doi was provided, stamp it into datasetID if none was provided.
    				if (key.equals(DwcTerm.datasetID.simpleName())) { 
    					if (value==null || value.trim().length()==0) { 
    	    				if (doi!=null && doi.length()>0) { 
    	    					value = doi;
    	    				}
    					}
    				}
    				
    				// write the value into the column
    				csvPrinter.print(value);
    			}
    			if (createExamples) { 
    				// add empty values for the resource relationship terms
    				csvPrinter.print("");
    				csvPrinter.print("");
    				csvPrinter.print("");
    				// end of line
    				csvPrinter.println();

    				// output a line containing a copy of the record, with ID values overwritten for use 
    				// as an example record (original values stored in resource relationship remarks).
    				Map<String,String>  metadataMap = new HashMap<String,String>();
    				Iterator<DwcTerm> itr = flatTerms.iterator();
    				StringBuilder sourceValues = new StringBuilder().append("{");
    				while (itr.hasNext()) {
    					DwcTerm term = itr.next();
    					String key = term.simpleName();
    					String value = dwcrecord.core().value(term);
    					if (key.equals(DwcTerm.occurrenceID.simpleName())) { 
    						sourceValues.append(key).append("=").append(value).append(" | ");
    						value = "urn:uuid:" + UUID.randomUUID().toString();
    						metadataMap.put(key, value);
    					}
    					if (key.equals(DwcTerm.institutionCode.simpleName())) { 
    						sourceValues.append(key).append("=").append(value).append(" | ");
    						value = "example.org";
    						metadataMap.put(key, value);
    					}
    					if (key.equals(DwcTerm.institutionID.simpleName())) { 
    						sourceValues.append(key).append("=").append(value).append(" | ");
    						value = "http://example.org/";
    						metadataMap.put(key, value);
    					}
    					if (key.equals(DwcTerm.collectionCode.simpleName())) { 
    						sourceValues.append(key).append("=").append(value).append(" | ");
    						value = "Modified Example";
    						metadataMap.put(key, value);
    					}
    					if (key.equals(DwcTerm.collectionID.simpleName())) {
    						sourceValues.append(key).append("=").append(value).append(" | ");
    						value = "urn:uuid:1887c794-7291-4005-8eee-1afbe9d7814e";
    						metadataMap.put(key, value);
    					}
    					csvPrinter.print(value);
    				}		
    				sourceValues.append("}");
    				// write the resource relationship colums pointing this record to its source.
    				csvPrinter.print(sourceOccurrenceID);
    				csvPrinter.print("source for modified example record");
    				metadataMap.put("sourceOccurrenceID", sourceOccurrenceID);
    				StringBuffer remarks = new StringBuffer().append("Example record derived from ").append(sourceOccurrenceID).append(" ");
    				remarks.append(sourceValues.toString()).append(" ");
    				if (doi!=null && doi.length()>0) { 
    				    metadataMap.put("sourceDatasetID", doi);
    					remarks.append("in DOI=").append(doi);
    				}
    				metadataMap.put("Modifications", "");
    				metadataMap.put("Tests", "");
    				//csvPrinter.print(remarks.toString());
    				csvPrinter.print(gson.toJson(metadataMap, new TypeToken<Map<String,String>>() {}.getType()));
    			}
    			csvPrinter.println();
    			csvPrinter.flush();

    			extractedRecords++;
    		}


    	}	
    	csvPrinter.close();

    	System.out.println("Extracted " + extractedRecords + " flat Darwin Core occurrence records");

	}
	
	protected List<String> getTargetOccIDList() { 
		if (targetOccIDList==null) { 
			targetOccIDList = new ArrayList<String>();
			if (targetOccurrenceIDs!=null && targetOccurrenceIDs.trim().length()>0) { 
				if (targetOccurrenceIDs.contains("|")) { 
					targetOccIDList.addAll(Arrays.asList(targetOccurrenceIDs.split("\\|")));
				} else { 
					targetOccIDList.add(targetOccurrenceIDs);
				}
			}
			while (targetOccIDList.contains("")) { 
				targetOccIDList.remove("");
			}
		}
		
		return targetOccIDList;
	}
	
}