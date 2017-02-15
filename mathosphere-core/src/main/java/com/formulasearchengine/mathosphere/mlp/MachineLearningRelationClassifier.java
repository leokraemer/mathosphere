package com.formulasearchengine.mathosphere.mlp;

import com.formulasearchengine.mathosphere.mlp.cli.MachineLearningDefinienClassifierConfig;
import com.formulasearchengine.mathosphere.mlp.contracts.JsonSerializerMapper;
import com.formulasearchengine.mathosphere.mlp.contracts.TextAnnotatorMapper;
import com.formulasearchengine.mathosphere.mlp.contracts.TextExtractorMapper;
import com.formulasearchengine.mathosphere.mlp.ml.WekaClassifier;
import com.formulasearchengine.mathosphere.mlp.pojos.ParsedWikiDocument;
import com.formulasearchengine.mathosphere.mlp.pojos.StrippedWikiDocumentOutput;
import com.formulasearchengine.mathosphere.mlp.pojos.WikiDocumentOutput;
import com.formulasearchengine.mathosphere.mlp.text.SimpleFeatureExtractorMapper;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.core.fs.FileSystem;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.util.stream.Collectors;

/**
 * Created by Leo on 10.02.2017.
 */
public class MachineLearningRelationClassifier {

  public static void find(MachineLearningDefinienClassifierConfig config) throws Exception {
    //parse wikipedia (subset) and process afterwards
    ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(config.getParallelism());
    DataSource<String> source = readWikiDump(config, env);
    DataSet<ParsedWikiDocument> documents = source.flatMap(new TextExtractorMapper())
      .map(new TextAnnotatorMapper(config));
    Logger.getRootLogger().setLevel(Level.ERROR);
    DataSet<WikiDocumentOutput> instances = documents.map(new SimpleFeatureExtractorMapper(config, null));
    //process parsed wikipedia
    DataSet<WikiDocumentOutput> result = instances.map(new WekaClassifier(config));
    DataSet<StrippedWikiDocumentOutput> stripped_result = result.map(stripSentenceMapper);
    //write and kick off flink execution
    stripped_result.map(new JsonSerializerMapper<>())
      .writeAsText(config.getOutputDir() + "/extractedDefiniens", FileSystem.WriteMode.OVERWRITE);
    env.execute();
  }

  public static DataSource<String> readWikiDump(MachineLearningDefinienClassifierConfig config, ExecutionEnvironment env) {
    return FlinkMlpRelationFinder.readWikiDump(config, env);
  }

  private static MapFunction<WikiDocumentOutput, StrippedWikiDocumentOutput> stripSentenceMapper =
    (MapFunction<WikiDocumentOutput, StrippedWikiDocumentOutput>) wikiDocumentOutput ->
      new StrippedWikiDocumentOutput(wikiDocumentOutput);
}