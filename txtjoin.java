///usr/bin/env jbang "$0" "$@" ; exit $?

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
			for ( var word : NON_WORD.split( CLEANUP_PATTERN.matcher( line ).replaceAll( "" ) ) ) {
				index.computeIfAbsent( word.toLowerCase( Locale.ROOT ), ignored -> new ArrayList<>() ).add( line );
			}
		} );
		return index;
	}
}