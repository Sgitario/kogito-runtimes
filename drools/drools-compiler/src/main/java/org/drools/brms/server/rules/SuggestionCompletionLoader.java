package org.drools.brms.server.rules;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.codehaus.jfdi.interpreter.ClassTypeResolver;
import org.drools.brms.client.modeldriven.SuggestionCompletionEngine;
import org.drools.brms.server.util.SuggestionCompletionEngineBuilder;
import org.drools.compiler.DrlParser;
import org.drools.compiler.DroolsParserException;
import org.drools.compiler.ParserError;
import org.drools.lang.descr.FactTemplateDescr;
import org.drools.lang.descr.FieldTemplateDescr;
import org.drools.lang.descr.GlobalDescr;
import org.drools.lang.descr.ImportDescr;
import org.drools.lang.descr.PackageDescr;
import org.drools.lang.dsl.DSLMapping;
import org.drools.lang.dsl.DSLMappingEntry;
import org.drools.lang.dsl.DSLMappingFile;
import org.drools.resource.util.ByteArrayClassLoader;
import org.drools.util.asm.ClassFieldInspector;

/**
 * This utility class loads suggestion completion stuff for the package configuration, 
 * introspecting from models, templates etc. 
 * 
 * This also includes DSL stuff, basically, everything you need to get started with a package.
 * It also validates the package configuration, and can provide errors.
 * 
 * This does NOT validate assets in the package, other then to load up DSLs, models etc
 * as needed.
 * 
 * FYI: the tests for this are in the BRMS module, in context of a full BRMS.
 * 
 * @author Michael Neale
 *
 */
public class SuggestionCompletionLoader {

    private final SuggestionCompletionEngineBuilder builder = new SuggestionCompletionEngineBuilder();
    private final DrlParser                         parser  = new DrlParser();
    private final ByteArrayClassLoader        loader;
    protected List                            errors = new ArrayList();

    public SuggestionCompletionLoader() {
        this.loader = new ByteArrayClassLoader( this.getClass().getClassLoader() );
    }

    /**
     * This will validate, and generate a new engine, ready to go.
     * If there are errors, you can get them by doing getErrors();
     * @param pkg
     * @return
     */
    public SuggestionCompletionEngine getSuggestionEngine(final String header,
                                                          final List jars,
                                                          final List dsls) {
        this.builder.newCompletionEngine();

        if ( !header.trim().equals( "" ) ) {
            processPackageHeader( header,
                                  jars );
        }

        // populating DSL sentences
        this.populateDSLSentences( dsls );

        return this.builder.getInstance();
    }

    private void processPackageHeader(final String header,
                                      final List jars) {
        // get fact types from imports
        PackageDescr pkgDescr;
        try {
            pkgDescr = this.parser.parse( header );
        } catch ( final DroolsParserException e1 ) {
            throw new IllegalStateException( "Serious error, unable to validate package." );
        }

        if ( this.parser.hasErrors() ) {
            for ( final Iterator iter = this.parser.getErrors().iterator(); iter.hasNext(); ) {
                final ParserError element = (ParserError) iter.next();
                this.errors.add( element.getMessage() );
            }
        }
        // populating information for the model itself
        this.populateModelInfo( pkgDescr,
                                jars );

        // populating globals
        this.populateGlobalInfo( pkgDescr,
                                 jars );

    }

    /**
     * @param pkg
     * @param errors
     */
    private void populateDSLSentences(final List dsls) {
        //        AssetItemIterator it = pkg.listAssetsByFormat( new String[]{AssetFormats.DSL} );
        //        while ( it.hasNext() ) {
        //            AssetItem item = (AssetItem) it.next();
        //            String dslData = item.getContent();
        //            DSLMappingFile file = new DSLMappingFile();
        //            try {
        //                if ( file.parseAndLoad( new StringReader( dslData ) ) ) {
        //                    DSLMapping mapping = file.getMapping();
        //                    for ( Iterator entries = mapping.getEntries().iterator(); entries.hasNext(); ) {
        //                        DSLMappingEntry entry = (DSLMappingEntry) entries.next();
        //                        if (entry.getSection() == DSLMappingEntry.CONDITION) {
        //                            builder.addDSLConditionSentence( entry.getMappingKey() );
        //                        } else if (entry.getSection() == DSLMappingEntry.CONSEQUENCE) {
        //                            builder.addDSLActionSentence( entry.getMappingKey() );
        //                        }
        //                        
        //                    }
        //                } else {
        //                    errors.add( file.getErrors().toString() );
        //                }
        //            } catch ( IOException e ) {
        //                errors.add( "Error while loading DSL language configuration : " + item.getBinaryContentAttachmentFileName() + " error message: " + e.getMessage() );
        //            }
        //        }

        for ( final Iterator it = dsls.iterator(); it.hasNext(); ) {
            final DSLMappingFile file = (DSLMappingFile) it.next();
            final DSLMapping mapping = file.getMapping();
            for ( final Iterator entries = mapping.getEntries().iterator(); entries.hasNext(); ) {
                final DSLMappingEntry entry = (DSLMappingEntry) entries.next();
                if ( entry.getSection() == DSLMappingEntry.CONDITION ) {
                    this.builder.addDSLConditionSentence( entry.getMappingKey() );
                } else if ( entry.getSection() == DSLMappingEntry.CONSEQUENCE ) {
                    this.builder.addDSLActionSentence( entry.getMappingKey() );
                }
            }
        }

    }

    /**
     * Populate the global stuff.
     */
    private void populateGlobalInfo(final PackageDescr pkgDescr,
                                    final List jars) {

        // populating information for the globals
        for ( final Iterator it = pkgDescr.getGlobals().iterator(); it.hasNext(); ) {
            final GlobalDescr global = (GlobalDescr) it.next();
            try {
                final String shortTypeName = global.getType();
                if ( !this.builder.hasFieldsForType( shortTypeName ) ) {
                    final Class clazz = loadClass( global.getType(),
                                             jars );
                    loadClassFields( clazz,
                                     shortTypeName );

                    this.builder.addGlobalType( global.getIdentifier(),
                                                shortTypeName );
                }

                this.builder.addGlobalType( global.getIdentifier(),
                                       shortTypeName );
            } catch ( final IOException e ) {
                this.errors.add( "Error while inspecting class for global: " + global.getType() + " error message: " + e.getMessage() );
            }

        }
    }

    /**
     * Populate the fact type data.
     */
    private void populateModelInfo(final PackageDescr pkgDescr,
                                   final List jars) {

        // iterating over the import list
        final ClassTypeResolver resolver = new ClassTypeResolver();
        for ( final Iterator it = pkgDescr.getImports().iterator(); it.hasNext(); ) {
            final ImportDescr imp = (ImportDescr) it.next();
            final String classname = imp.getTarget();
            resolver.addImport( classname );

            final Class clazz = loadClass( classname,
                                     jars );
            if ( clazz != null ) {
                try {
                    final String shortTypeName = getShortNameOfClass( clazz.getName() );
                    loadClassFields( clazz,
                                     shortTypeName );
                    this.builder.addFactType( shortTypeName );
                } catch ( final IOException e ) {
                    this.errors.add( "Error while inspecting the class: " + classname + ". The error was: " + e.getMessage() );
                }
            }
        }

        // iterating over templates
        populateFactTemplateTypes( pkgDescr,
                                   resolver );
    }

    /**
     * Iterates over fact templates and add them to the model definition
     * 
     * @param pkgDescr
     */
    private void populateFactTemplateTypes(final PackageDescr pkgDescr,
                                           final ClassTypeResolver resolver) {
        for ( final Iterator it = pkgDescr.getFactTemplates().iterator(); it.hasNext(); ) {
            final FactTemplateDescr templ = (FactTemplateDescr) it.next();
            final String factType = templ.getName();
            this.builder.addFactType( factType );

            final String[] fields = new String[templ.getFields().size()];
            this.builder.addFieldsForType( factType,
                                      fields );

            int index = 0;
            for ( final Iterator fieldsIt = templ.getFields().iterator(); fieldsIt.hasNext(); ) {
                final FieldTemplateDescr fieldDescr = (FieldTemplateDescr) fieldsIt.next();
                fields[index++] = fieldDescr.getName();
                final String fieldType = fieldDescr.getClassType();

                Class fieldTypeClass = null;
                try {
                    fieldTypeClass = resolver.resolveType( fieldType );
                } catch ( final ClassNotFoundException e ) {
                    this.errors.add( "Fact template field type not found: " + fieldType );
                }
                this.builder.addFieldType( factType + "." + fieldDescr.getName(),
                                      getFieldType( fieldTypeClass ) );
            }
        }
    }

    private void loadClassFields(final Class clazz,
                                 final String shortTypeName) throws IOException {
        if ( clazz == null ) {
            return;
        }
        final ClassFieldInspector inspector = new ClassFieldInspector( clazz );
        String[] fields = (String[]) inspector.getFieldNames().keySet().toArray( new String[inspector.getFieldNames().size()] );

        fields = removeIrrelevantFields( fields );

        this.builder.addFieldsForType( shortTypeName,
                                  fields );
        for ( int i = 0; i < fields.length; i++ ) {
            final Class type = (Class) inspector.getFieldTypes().get( fields[i] );
            final String fieldType = getFieldType( type );
            this.builder.addFieldType( shortTypeName + "." + fields[i],
                                  fieldType );
        }
    }

    String getShortNameOfClass(final String clazz) {
        return clazz.substring( clazz.lastIndexOf( '.' ) + 1 );
    }

    /**
     * This will remove the unneeded "fields" that come from java.lang.Object
     * these are really not needed for the modeller.
     */
    String[] removeIrrelevantFields(final String[] fields) {
        final List result = new ArrayList();
        for ( int i = 0; i < fields.length; i++ ) {
            final String field = fields[i];
            if ( field.equals( "class" ) || field.equals( "hashCode" ) || field.equals( "toString" ) ) {
                //ignore
            } else {
                result.add( field );
            }
        }
        return (String[]) result.toArray( new String[result.size()] );
    }

    /**
     * @param pkg
     * @param classname
     * @param clazz
     * @return
     */
    private Class loadClass(final String classname,
                            final List jars) {
        Class clazz = null;
        try {
            // check if it is already in the classpath
            clazz = this.loader.loadClass( classname );

        } catch ( final ClassNotFoundException e1 ) {

            // not found in the classpath, so check if it
            // is in a package model
            try {
                addJars( jars );
                clazz = this.loader.loadClass( classname );
            } catch ( final IOException e ) {
                throw new IllegalStateException( e.getMessage() );
            } catch ( final ClassNotFoundException e ) {
                this.errors.add( "Class not found: " + classname );
            }
        }
        return clazz;
    }

    /**
     * This will add the given jars to the classloader.
     */
    private void addJars(final List jars) throws IOException {
        for ( final Iterator it = jars.iterator(); it.hasNext(); ) {
            final JarInputStream jis = (JarInputStream) it.next();
            JarEntry entry = null;
            final byte[] buf = new byte[1024];
            int len = 0;
            while ( (entry = jis.getNextJarEntry()) != null ) {
                if ( !entry.isDirectory() ) {
                    final ByteArrayOutputStream out = new ByteArrayOutputStream();
                    while ( (len = jis.read( buf )) >= 0 ) {
                        out.write( buf,
                                   0,
                                   len );
                    }
                    this.loader.addResource( entry.getName(),
                                        out.toByteArray() );
                }
            }

        }
    }

    /**
     * @param inspector
     * @param fields
     * @param i
     * @return
     */
    private String getFieldType(final Class type) {
        String fieldType = null; // if null, will use standard operators
        if ( type != null ) {
            if ( type.isPrimitive() && (type != boolean.class) ) {
                fieldType = SuggestionCompletionEngine.TYPE_NUMERIC;
            } else if ( Number.class.isAssignableFrom( type ) ) {
                fieldType = SuggestionCompletionEngine.TYPE_NUMERIC;
            } else if ( String.class.isAssignableFrom( type ) ) {
                fieldType = SuggestionCompletionEngine.TYPE_STRING;
            } else if ( Collection.class.isAssignableFrom( type ) ) {
                fieldType = SuggestionCompletionEngine.TYPE_COLLECTION;
            } else if ( Comparable.class.isAssignableFrom( type ) ) {
                fieldType = SuggestionCompletionEngine.TYPE_COMPARABLE;
            }
        }
        return fieldType;
    }

    /**
     * @return true if there were errors when processing the package.
     */
    public boolean hasErrors() {
        return (this.errors.size() > 0);
    }

    /**
     * Returns a list of String errors.
     */
    public List getErrors() {
        return this.errors;
    }

}
