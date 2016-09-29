package com.formulasearchengine.mathosphere.mlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formulasearchengine.mathosphere.mlp.cli.EvalCommandConfig;
import com.formulasearchengine.mathosphere.mlp.cli.FlinkMlpCommandConfig;
import com.formulasearchengine.mathosphere.mlp.contracts.*;
import com.formulasearchengine.mathosphere.mlp.evaluation.EvaluatedRelation;
import com.formulasearchengine.mathosphere.mlp.evaluation.EvaluatedWikiDocumentOutput;
import com.formulasearchengine.mathosphere.mlp.pojos.*;
import com.formulasearchengine.mathosphere.mlp.text.WikiTextUtils;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.io.TextInputFormat;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.core.fs.FileSystem.WriteMode;
import org.apache.flink.core.fs.Path;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class FlinkMlpRelationFinder {

  public static void main(String[] args) throws Exception {
    FlinkMlpCommandConfig config = FlinkMlpCommandConfig.from(args);
    run(config);
  }

  public static void run(FlinkMlpCommandConfig config) throws Exception {
    ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

    DataSource<String> source = readWikiDump(config, env);
    DataSet<ParsedWikiDocument> documents =
      source.flatMap(new TextExtractorMapper())
        .map(new TextAnnotatorMapper(config));

    DataSet<WikiDocumentOutput> result = documents.map(new CreateCandidatesMapper(config));

    result.map(new JsonSerializerMapper<>())
      .writeAsText(config.getOutputDir(), WriteMode.OVERWRITE);
    //int cores = Runtime.getRuntime().availableProcessors();
    //env.setParallelism(1); // rounds down
    final int parallelism = config.getParallelism();
    if (parallelism > 0) {
      env.setParallelism(parallelism);
    }
    env.execute("Relation Finder");
  }

  public String runFromText(FlinkMlpCommandConfig config, String input) throws Exception {
    final JsonSerializerMapper<Object> serializerMapper = new JsonSerializerMapper<>();
    return serializerMapper.map(outDocFromText(config, input));
  }

  public WikiDocumentOutput outDocFromText(FlinkMlpCommandConfig config, String input) throws Exception {
    final TextAnnotatorMapper textAnnotatorMapper = new TextAnnotatorMapper(config);
    textAnnotatorMapper.open(null);
    final CreateCandidatesMapper candidatesMapper = new CreateCandidatesMapper(config);

    final ParsedWikiDocument parsedWikiDocument = textAnnotatorMapper.parse(input);
    return candidatesMapper.map(parsedWikiDocument);
  }

  public static DataSource<String> readWikiDump(FlinkMlpCommandConfig config, ExecutionEnvironment env) {
    Path filePath = new Path(config.getDataset());
    TextInputFormat inp = new TextInputFormat(filePath);
    inp.setCharsetName("UTF-8");
    inp.setDelimiter("</page>");
    return env.readFile(inp, config.getDataset());
  }

  public static DataSource<String> readAnnotatedWikiDump(EvalCommandConfig config, ExecutionEnvironment env) {
    Path filePath = new Path(config.getDataset());
    TextInputFormat inp = new TextInputFormat(filePath);
    inp.setCharsetName("UTF-8");
    return env.readFile(inp, config.getDataset());
  }


  /**
   * Runs a collection of wikipedia pages through the mpl pipeline and evaluates them against a provided gold standard.
   *
   * @param config Specify input, output and other parameters here.
   * @throws IOException If an input file was parsed incorrectly.
   */
  public static void evaluate(EvalCommandConfig config) throws Exception {
    ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
    final CreateCandidatesMapper candidatesMapper = new CreateCandidatesMapper(config);
    DataSet<ParsedWikiDocument> documents;
    if (config.getNoExtract()) {
      JsonDeserializerMapper deserializer = new JsonDeserializerMapper();
      DataSource<String> source = readAnnotatedWikiDump(config, env);
      documents = source.flatMap(deserializer);
    }
    else {
      DataSource<String> source = readWikiDump(config, env);
      documents =
        source.flatMap(new TextExtractorMapper())
          .map(new TextAnnotatorMapper(config));
    }
    documents.map(new JsonSerializerMapper<>()).writeAsText(config.getOutputDir() + "-annotated.json", WriteMode.OVERWRITE).setParallelism(1);
    Map<String, Object> gold = getGoldDataFromJson(config);
    MapFunction<ParsedWikiDocument, EvaluatedWikiDocumentOutput> checker = new MapFunction<ParsedWikiDocument, EvaluatedWikiDocumentOutput>() {
      @Override
      public EvaluatedWikiDocumentOutput map(ParsedWikiDocument parsedWikiDocument) {
        String title = parsedWikiDocument.getTitle().replaceAll(" ", "_");
        System.err.println(title + "###################START#######################");
        //get gold standard entry for the wiki article
        Map goldElement = (Map) gold.get(title);
        //get definition of the formula in the gold standard
        Map formula = (Map) goldElement.get("formula");
        final Integer fid = Integer.parseInt((String) formula.get("fid"));
        final String tex = (String) formula.get("math_inputtex");
        int pos = getFormulaPos(parsedWikiDocument, fid);
        //get the formula from the wiki document
        final MathTag seed = parsedWikiDocument.getFormulas().get(pos);
        //sanity check if the formula from the gold standard was found in the wiki document
        if (!seed.getContent().equals(tex)) {
          System.err.println("PROBLEM WITH" + title);
          System.err.println(seed.getContent());
          System.err.println(tex);
          System.err.println("Invalid numbering.");
          System.err.println("Problem with " + title);
          //add minimal error information
          EvaluatedWikiDocumentOutput errorResult = new EvaluatedWikiDocumentOutput(false);
          errorResult.setTitle(parsedWikiDocument.getTitle());
          return new EvaluatedWikiDocumentOutput(false);
        }
        //get the found identifiers for the formula from the wiki document
        final Set<String> real = seed.getIdentifiers(config).elementSet();
        //get the definitions from the gold standard
        final Map definitions = (Map) goldElement.get("definitions");
        //get the expected identifiers from the gold standard
        final Set expected = definitions.keySet();
        //calculate precision and recall
        Set<String> tp = new HashSet<>(expected);
        Set<String> fn = new HashSet<>(expected);
        Set<String> fp = new HashSet<>(real);
        fn.removeAll(real);
        fp.removeAll(expected);
        tp.retainAll(real);
        System.err.println("Identifier extraction: false negatives: " + fn.size() + " false positives: " + fp.size() + " true positives: " + tp.size());
        double rec = ((double) tp.size()) / (tp.size() + fn.size());
        double prec = ((double) tp.size()) / (tp.size() + fp.size());
        if (rec < 1. || prec < 1.) {
          System.err.println(title + " $" + tex + "$ Precision " + prec + "; Recall " + rec);
          System.err.println("fp:" + fp.toString());
          System.err.println("fn:" + fn.toString());
          System.err.println("https://en.formulasearchengine.com/wiki/" + title + "#math." + formula.get("oldId") + "." + fid);
        } else {
          System.err.println(title + " - every expected identifier was found. Checking the definitions now.");
        }
        //search for identifiers and their definitions
        final WikiDocumentOutput wikiDocumentOutput = candidatesMapper.map(parsedWikiDocument);
        EvaluatedWikiDocumentOutput evaluatedWikiDocumentOutput = new EvaluatedWikiDocumentOutput(wikiDocumentOutput);
        evaluatedWikiDocumentOutput.setGold(gold);
        evaluatedWikiDocumentOutput.setIdentifierExtractionPrecision(prec);
        evaluatedWikiDocumentOutput.setIdentifierExtractionRecall(rec);
        List<Relation> relations = wikiDocumentOutput.getRelations();
        //only look at the definition for the identifiers contained in the true positive set
        relations.removeIf(r -> !tp.contains(r.getIdentifier()));
        List<EvaluatedRelation> evaluatedRelations = new ArrayList<>();
        int falseDefinitions = 0;
        int correctDefinitions = 0;
        int totalDefinitions = 0;
        for (Relation relation : relations) {
          EvaluatedRelation evaluatedRelation = new EvaluatedRelation(relation);
          evaluatedRelations.add(evaluatedRelation);
          final List<String> refList = getDefiniens(definitions, relation);
          final String definition = relation.getDefinition().replaceAll("(\\[\\[|\\]\\])", "");
          int index = refList.indexOf(definition);
          if (index > -1) {
            evaluatedRelation.setGoldDefinition(refList.get(index));
            //if (refList.get(0).equals(definition)) {
            System.err.print("Correct: ");
            correctDefinitions++;
          } else {
            evaluatedRelation.setGoldDefinition(refList.get(0));
            System.err.print("Wrong: ");
            falseDefinitions++;
          }

          totalDefinitions++;
          System.err.println(relation.getIdentifier() + " expected one out of: " + refList.toString() + " was: " + relation.getDefinition() + " score: " + relation.getScore());
          /*
          Set<String> tp = new HashSet<>(expected);
        Set<String> fn = new HashSet<>(expected);
        Set<String> fp = new HashSet<>(real);
        fn.removeAll(real);
        fp.removeAll(expected);
        tp.retainAll(real);
        List expectedDescriptions = (List) definitions.get(relation.getIdentifier());
        Set<String> tpD = new HashSet<>(expectedDescriptions);
        Set<String> fnD = new HashSet<>(expectedDescriptions);
        Set<String> fpD = new HashSet<>(real);
        fnD.removeAll(real);
        fpD.removeAll(expected);
        tpD.retainAll(real);
        double descriptionPrecision = correctDefinitions / totalDefinitions;
        double descriptionRecall = correctDefinitions / totalDefinitions;
        System.err.print("Definition presicion: " + descriptionPrecision + " definition recall: " + descriptionRecall);
        */
        }
        List<String> realDecriptionExtractions = evaluatedRelations.stream()
          .map(r -> r.getIdentifier())
          .collect(Collectors.toList());
        Set<String> tpDefinitionExtractions = new HashSet(definitions.keySet());
        Set<String> fnDefinitionExtractions = new HashSet(definitions.keySet());
        Set<String> fpDefinitionExtractions = new HashSet(realDecriptionExtractions);
        tpDefinitionExtractions.retainAll(realDecriptionExtractions);
        fnDefinitionExtractions.removeAll(realDecriptionExtractions);
        fpDefinitionExtractions.removeAll(definitions.keySet());
        double descriptionExtractionRecall = ((double) tpDefinitionExtractions.size()) / (tpDefinitionExtractions.size() + fnDefinitionExtractions.size());
        double descriptionExtractionPrecision = ((double) tpDefinitionExtractions.size()) / (tpDefinitionExtractions.size() + fpDefinitionExtractions.size());
        evaluatedWikiDocumentOutput.setRelations(evaluatedRelations);
        evaluatedWikiDocumentOutput.setDescriptionExtractionPrecision(descriptionExtractionPrecision);
        evaluatedWikiDocumentOutput.setDescriptionExtractionRecall(descriptionExtractionRecall);
        System.err.println("Summary " + title + " correct definitions: " + correctDefinitions + " incorrect definitions: " + falseDefinitions);
        System.err.println("total definitions: " + totalDefinitions + " correct definitions: " + correctDefinitions + " wrong definitions: " + falseDefinitions);
        System.err.println("Definition extraction precision: " + descriptionExtractionPrecision + " recall: " + descriptionExtractionRecall);
        System.err.println("tp: " + tpDefinitionExtractions.size() + " fn: " + fnDefinitionExtractions.size() + " fp: " + fpDefinitionExtractions.size());
        System.err.println("Expected number of extracted definitions: " + definitions.keySet().size());
        System.err.println(title + "###################END#######################");
        return evaluatedWikiDocumentOutput;
      }
    };

    DataSet<EvaluatedWikiDocumentOutput> evaluatedDocuments = documents.map(checker);
    evaluatedDocuments.map(new JsonSerializerMapper<>()).writeAsText(config.getOutputDir() + "-evaluated.json", WriteMode.OVERWRITE).setParallelism(1);
    env.setParallelism(1);
    env.execute("Evaluate Performance");
    //documents.writeAsText(config.getOutputDir(), WriteMode.OVERWRITE);
    // rounds down
    //env.execute("Evaluate Performance");
//    System.exit(0);
//
//    DataSet<WikiDocumentOutput> result = documents.map(new CreateCandidatesMapper(config));
//
//    result.map(new JsonSerializerMapper<>())
//        .writeAsText(config.getOutputDir(), WriteMode.OVERWRITE);
//    //int cores = Runtime.getRuntime().availableProcessors();
//    //env.setParallelism(1); // rounds down
//    env.execute("Evaluate Performance");
  }


  public static Map<String, Object> getGoldDataFromJson(EvalCommandConfig config) throws java.io.IOException {
    return getGoldDataFromJson(config.getQueries());
  }

  public static Map<String, Object> getGoldDataFromJson(String filename) throws java.io.IOException {
    final File file = new File(filename);
    ObjectMapper mapper = new ObjectMapper();
    List userData = mapper.readValue(file, List.class);
    Map<String, Object> gold = new HashMap<>();
    for (Object o : userData) {
      final Map entry = (Map) o;
      Map formula = (Map) entry.get("formula");
      gold.put((String) formula.get("title"), o);
    }
    return gold;
  }

  public static List<String> getDefiniens(Map definitions, Relation relation) {
    List<String> result = new ArrayList<>();
    List definiens = (List) definitions.get(relation.getIdentifier());
    for (Object definien : definiens) {
      if (definien instanceof Map) {
        Map<String, String> var = (Map) definien;
        for (Map.Entry<String, String> stringStringEntry : var.entrySet()) {
          // there is only one entry
          final String def = stringStringEntry.getValue().trim().replaceAll("\\s*\\(.*?\\)$", "");
          result.add(def);
        }
      } else {
        result.add((String) definien);
      }
    }
    return result;
  }

  /**
   * Get the fid'th tex-formula from the document
   *
   * @param parsedWikiDocument
   * @param fid
   * @return where to find the formula in the document, -1 if not existent.
   */
  private static int getFormulaPos(ParsedWikiDocument parsedWikiDocument, Integer fid) {
    int count = -1;
    int i;
    for (i = 0; i < parsedWikiDocument.getFormulas().size(); i++) {
      final MathTag t = parsedWikiDocument.getFormulas().get(i);
      if (t.getMarkUpType() == WikiTextUtils.MathMarkUpType.LATEX) {
        count++;
        if (count == fid) {
          break;
        }
      }
    }
    return i;
  }
}
