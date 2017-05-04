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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
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

/**
 * Read occurrence data from DarwinCore Archive files.
 * 
 * @author mole
 *
 */
public class DwCaExtractor {
	
	private static final Log logger = LogFactory.getLog(DwCaExtractor.class);

    @Option(name="-o",usage="output csv file",aliases="--output")
    private String outputFilename = "output.csv";
    
    @Option(name="-l",usage="maximum number of records to read",aliases="--limit")
    private int recordLimit = 0; // maximum records to read, zero or less for no limit.
    
    @Option(name="-i",usage="input Darwin Core Archive file",required=true,aliases="--input")
    private String archiveFilePath = "dwca.zip";
    
    @Option(name="-d",usage="DOI for the source dataset",aliases="--doi")
    private String doi = "";
    
    private int cValidRecords = 0;  // number of records read.

    public Archive dwcArchive = null;
    public CSVPrinter csvPrinter = null;
    public Reader inputReader = null;
    public String recordClass = null;
    public String[] headers = new String[]{};

    /**
     * Report reading records in this increment. 
     */
    private int reportSize = 1000;
    
    Iterator<StarRecord> iterator;

    long start;	    
	
	public static void main(String[] args) {
		
		DwCaExtractor extractor = new DwCaExtractor();
		if (extractor.setup(args)) { 
			try {
				extractor.extract();
			} catch (IOException e) {
				logger.error(e.getMessage(),e);
			}
		}
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
		parser.setUsageWidth(4096);
		try {
			parser.parseArgument(args);	

			if (archiveFilePath != null) { 
				String filePath = archiveFilePath;
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
							System.out.println("Unzipped archive into " + outputDirectory.getPath());
						} catch (FileNotFoundException e) {
							logger.error(e.getMessage());
							e.printStackTrace();
						} catch (IOException e) {
							logger.error(e.getMessage());
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					// look into the unzipped directory
					dwcArchive = openArchive(outputDirectory);
				}
				if (dwcArchive!=null) { 
					if (checkArchive()) {
						
						// Check output 
						csvPrinter = new CSVPrinter(new FileWriter(outputFilename, true), CSVFormat.DEFAULT.withQuoteMode(QuoteMode.NON_NUMERIC));
						// no exception thrown
						setupOK = true;
					}
				} else { 
					System.out.println("Problem opening archive.");
				}
				
				
			}

		} catch( CmdLineException e ) {
			System.err.println(e.getMessage());
			parser.printUsage(System.err);
		} catch (IOException e) {
			System.err.println(e.getMessage());
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
		System.out.println("Core file found: " + dwcArchive.getCore().getLocations());
		System.out.println("Core row type: " + dwcArchive.getCore().getRowType());
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
		        System.out.println("Cannot find " + term + " in core of input dataset.");
		      }
		    } 		
		    
		    result = true;
		} else { 
			// currently can only process occurrence core
		}

        return result;
    }
    
    /**
	 * @return the reportSize (send count of number of records read
	 * to the console at this increment of number of records read)
	 */
	public int getReportSize() {
		return reportSize;
	}

	/**
	 * @param reportSize the reportSize to set
	 */
	public void setReportSize(int chunkSize) {
		this.reportSize = chunkSize;
	}  
		
	protected void extract() throws IOException {
			
		List<DwcTerm> flatTerms = DwcTerm.listByGroup(DwcTerm.GROUP_RECORD);
		flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_OCCURRENCE));
		flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_IDENTIFICATION));
		flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_LOCATION));
		flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_EVENT));
		flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_GEOLOGICALCONTEXT));
		flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_TAXON));
		flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_MATERIAL_SAMPLE));
		flatTerms.addAll(DwcTerm.listByGroup(DwcTerm.GROUP_ORGANISM));
		
		Iterator<DwcTerm> ih = flatTerms.iterator();
		while (ih.hasNext()) { 
			String name = ih.next().simpleName();
			csvPrinter.print(name);
		}
		
		csvPrinter.println();
		csvPrinter.flush();
		
	    Iterator<StarRecord> iterator = dwcArchive.iterator();
		Set<Term> terms = dwcArchive.getCore().getTerms();
		Iterator<Term> i = terms.iterator();
		while (i.hasNext()) { 
			System.out.println(i.next().simpleName());
		}
	    while (iterator.hasNext() && (cValidRecords < recordLimit || recordLimit==0)) {
			// read initial set of rows, pass downstream
			StarRecord dwcrecord = iterator.next();

			Iterator<DwcTerm> it = flatTerms.iterator();
			while (it.hasNext()) {
				DwcTerm term = it.next();
				String key = term.simpleName();
			    String value = dwcrecord.core().value(term);
			    
			    csvPrinter.print(value);
            }
            csvPrinter.println();
            csvPrinter.flush();
			
			cValidRecords++;
		}	
	    csvPrinter.close();
	
		System.out.println("Read " + reportSize + " records, total " + cValidRecords);
			

	}
	
}
