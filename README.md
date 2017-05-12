A very simple utility to extract records and create copies of them as example data from Darwin Core archive files.

Written as a simple utility to produce flat Darwin Core example data from real data for the Kurator project supporting work in the TDWG Data Quality interest group.  Modified from the Darwin Core data loading actor in FP-Akka https://github.com/FilteredPush/FP-Akka 

To build:

    mvn package

To run

    $ java -jar target/dwca_extractor-0.0.1-SNAPSHOT-jar-with-dependencies.jar -h
    
    Option "-i (--input)" is required
     -a (--append)                : append to the output csv file instead of
                                    overwriting (default: false)
     -c (--cherry-pick) VAL       : Cherry pick only particular occurrence records
                                    (pipe delimited list of occurrenceIds) 
                                    (default: )
     -d (--doi) VAL               : DOI for the source dataset (default: )
     -e (--create-example-copies) : Duplicate records into example records with
                                    newly minted uuid occurrenceId  (default: false)
     -h (--help)                  : Display this help message.  (default: true)
     -i (--input) VAL             : input Darwin Core Archive file
     -l (--limit) N               : maximum number of records to read (default: 0)
     -o (--output) VAL            : output csv file (default: output.csv)

Example usage: 

Visit: http://www.gbif.org/dataset/b9f90d91-53c5-4c0f-b950-5678a7ecd571, download the source Darwin Core archive https://data.gbif.no/ipt/archive.do?r=foram-horten then run: 

   java -jar dwca_extractor-0.0.1-SNAPSHOT-jar-with-dependencies.jar -i dwca-foram-horten-v1.1.zip -l 1 -o output.csv -e -d  http://doi.org/10.15468/pvkoqy  

This will extract the first record from the archive (-l 1), create a duplicate example copy (-e) assert the doi of the source data set in the example record and as the dwc:datasetID of the extracted record (-d doi), and save the extracted record and example record to output.csv.


