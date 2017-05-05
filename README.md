A very simple utility to extract records and create copies of them as example data from Darwin Core archive files.

Written as a simple utility to produce flat Darwin Core example data from real data for the Kurator project supporting work in the TDWG Data Quality interest group.

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
