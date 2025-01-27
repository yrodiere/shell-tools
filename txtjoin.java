///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.apache.lucene:lucene-analysis-common:9.12.1

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;

class txtjoin {

	private static final Pattern NON_WORD = Pattern.compile( "\\W+" );
	// Ignore same-domain matches
	private static final Pattern CLEANUP_PATTERN = Pattern.compile( "(?<=@)[^,]+(?=,|$)" );

	public static void main(String[] args) throws Exception {
		if ( args.length != 2 ) {
			System.out.println( "Missing files to compare" );
			System.exit( 1 );
		}
		String first = args[0];
		String second = args[1];
		Map<String, List<String>> firstIndex = index( first );
		Map<String, List<String>> secondIndex = index( second );
		Map<String, Set<String>> matches = new LinkedHashMap<>();
		firstIndex.forEach( (word, linesInFirst) -> {
			var linesInSecond = secondIndex.get( word );
			if ( linesInSecond != null ) {
				for ( var line : linesInFirst ) {
					matches.computeIfAbsent( line, ignored -> new LinkedHashSet<>() )
							.addAll( linesInSecond );
				}
			}
		} );
		System.out.println( "Matches:" );
		matches.forEach( (lineInFirst, linesInSecond) -> {
			System.out.println( "\tFirst file: " + lineInFirst );
			System.out.println( "\tSecond file: " );
			for ( var line : linesInSecond ) {
				System.out.println( "\t\t" + line );
			}
		} );
		System.out.println( "End of matches." );
	}

	public static Map<String, List<String>> index(String fileName) throws IOException {
		Map<String, List<String>> index = new LinkedHashMap<>();
		Files.lines( Path.of( fileName ) ).forEach( line -> {
			for ( var word : NON_WORD.split( cleanup( line ) ) ) {
				index.computeIfAbsent( word.toLowerCase( Locale.ROOT ), ignored -> new ArrayList<>() ).add( line );
			}
		} );
		return index;
	}

	private static String cleanup(String text) {
		return CLEANUP_PATTERN.matcher( foldAscii( text ) ).replaceAll( "" );
	}

	private static String foldAscii(String text) {
		char[] input = text.toCharArray();
		char[] output = new char[4 * text.length()]; // No idea why, but that's what the filter seems to do, and memory is cheap.
		int outputPos = ASCIIFoldingFilter.foldToASCII( input, 0, output, 0, input.length );
		return new String( output, 0, outputPos );
	}
}