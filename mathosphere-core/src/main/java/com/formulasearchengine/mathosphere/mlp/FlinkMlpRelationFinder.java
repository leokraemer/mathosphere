package com.formulasearchengine.mathosphere.mlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formulasearchengine.mathosphere.mlp.cli.EvalCommandConfig;
import com.formulasearchengine.mathosphere.mlp.cli.FlinkMlpCommandConfig;
import com.formulasearchengine.mathosphere.mlp.contracts.*;
import com.formulasearchengine.mathosphere.mlp.evaluation.EvaluatedRelation;
import com.formulasearchengine.mathosphere.mlp.evaluation.EvaluatedWikiDocumentOutput;
import com.formulasearchengine.mathosphere.mlp.pojos.*;
import com.formulasearchengine.mathosphere.mlp.text.WikiTextUtils;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.typeinfo.IntegerTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.aggregation.Aggregations;
import org.apache.flink.api.java.io.TextInputFormat;
import org.apache.flink.api.java.operators.AggregateOperator;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.core.fs.FileSystem.WriteMode;
import org.apache.flink.core.fs.Path;
import org.apache.flink.util.Collector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.flink.api.common.typeinfo.BasicTypeInfo.INT_TYPE_INFO;
import static org.apache.flink.api.common.typeinfo.BasicTypeInfo.STRING_TYPE_INFO;
import static org.apache.flink.api.java.aggregation.Aggregations.SUM;

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
    DataSet<ParsedWikiDocument> documents;
    DataSource<String> source = readWikiDump(config, env);
    documents =
      source.flatMap(new TextExtractorMapper())
        .map(new TextAnnotatorMapper(config));
    Map<String, Object> gold = getGoldDataFromJson(config);
    final CreateCandidatesMapper candidatesMapper = new CreateCandidatesMapper(config);
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
        EvaluatedWikiDocumentOutput result = new EvaluatedWikiDocumentOutput(wikiDocumentOutput);
        result.setGold(goldElement);
        result.setIdentifierExtractionPrecision(prec);
        result.setIdentifierExtractionRecall(rec);
        List<Relation> relations = wikiDocumentOutput.getRelations();
        //only look at the definition for the identifiers contained in the true positive set
        relations.removeIf(r -> !tp.contains(r.getIdentifier()));
        List<EvaluatedRelation> evaluatedRelations = new ArrayList<>();
        int falseDefinitions = 0;
        int correctDefinitions = 0;
        int totalDefinitions = 0;
        Set<IdentifierDefinition> expectedDef = new HashSet<>();
        for (Relation relation : relations) {
          EvaluatedRelation evaluatedRelation = new EvaluatedRelation(relation);
          List previousFinds = evaluatedRelations.stream()
            .filter(er -> er.getIdentifier().equals(evaluatedRelation.getIdentifier()) && er.getDefinition().equals(evaluatedRelation.getDefinition())
            ).collect(Collectors.toList());
          evaluatedRelations.add(evaluatedRelation);
          final List<String> refList = getDefiniens(definitions, relation);
          for (String definiens : refList) {
            expectedDef.add(new IdentifierDefinition(relation.getIdentifier(), definiens.replaceAll("(\\[\\[|\\]\\])", "")));
          }
          final String definition = relation.getDefinition().replaceAll("(\\[\\[|\\]\\])", "");
          int index = refList.indexOf(definition);
          if (index > -1) {
            evaluatedRelation.setGoldDefinition(refList.get(index));
            //if (refList.get(0).equals(definition)) {
            System.err.print("Correct: ");
            if (previousFinds.size() == 0) {
              correctDefinitions++;
            } else {
              System.err.print("This definition was found before.");
            }
          } else {
            evaluatedRelation.setGoldDefinition(refList.get(0));
            System.err.print("Wrong: ");
            falseDefinitions++;
          }

          totalDefinitions++;
          System.err.println(relation.getIdentifier() + " expected one out of: " + refList.toString() + " was: " + relation.getDefinition() + "\t score: " + relation.getScore());
        }

        List<IdentifierDefinition> realDef = evaluatedRelations.stream().map(e -> new IdentifierDefinition(e)).collect(Collectors.toList());
        realDef.forEach(e -> e.setDefinition(e.getDefinition().replaceAll("(\\[\\[|\\]\\])", "")));
        Set<IdentifierDefinition> tpDE = new HashSet(expectedDef);
        Set<IdentifierDefinition> fnDE = new HashSet(expectedDef);
        Set<IdentifierDefinition> fpDE = new HashSet(realDef);
        tpDE.retainAll(realDef);
        fnDE.removeAll(realDef);
        fpDE.removeAll(expectedDef);
        /*
        tp = Set<>(expected);
        fn =Set<>(expected);
        fp = Set<>(real);
        fn.removeAll(real);
        fp.removeAll(expected);
        tp.retainAll(real);
        */
        double dRecall = ((double) tpDE.size()) / (tpDE.size() + fnDE.size());
        double dPrecision = ((double) tpDE.size()) / (tpDE.size() + fpDE.size());
        result.setTruePositives(tpDE);
        result.setFalseNegatives(fnDE);
        result.setFalsePositives(fpDE);
        result.setRelations(evaluatedRelations);
        result.setDescriptionExtractionPrecision(dPrecision);
        result.setDescriptionExtractionRecall(dRecall);
        syserr(title, definitions, falseDefinitions, correctDefinitions, totalDefinitions, tpDE, fnDE, fpDE, dRecall, dPrecision);
        return result;
      }
    };

    DataSet<EvaluatedWikiDocumentOutput> evaluatedDocuments = documents.map(checker);
    evaluatedDocuments.map(new MapFunction<EvaluatedWikiDocumentOutput, Object>() {
      @Override
      public Object map(EvaluatedWikiDocumentOutput doc) throws Exception {
        System.err.println(doc.getTitle() + ", " + doc.getFalseNegatives().size() + ", " + doc.getFalsePositives() + ", " + doc.getTruePositives());
        return doc;
      }
    });
    DataSet<Tuple4<String, Integer, Integer, Integer>> fnfptpData = evaluatedDocuments.map
      (d -> new Tuple4<String, Integer, Integer, Integer>(
        d.getTitle(),
        d.getFalseNegatives().size(),
        d.getFalsePositives().size(),
        d.getTruePositives().size())
      ).returns(new TupleTypeInfo(Tuple4.class, STRING_TYPE_INFO, INT_TYPE_INFO, INT_TYPE_INFO, INT_TYPE_INFO));
    fnfptpData.map(new MapFunction<Tuple4<String,Integer,Integer,Integer>, Tuple4<String,Integer,Integer,Integer>>() {
      @Override
      public Tuple4<String, Integer, Integer, Integer> map(Tuple4<String, Integer, Integer, Integer> value) throws Exception {
        System.err.println(value.toString());
        return value;
      }
    });
    fnfptpData
      .sum(1).andSum(2).andSum(3)
      .map(t -> new Tuple4<String, Integer, Integer, Integer>("Summary: ", t.f1, t.f2, t.f3))
      .returns(new TupleTypeInfo(Tuple4.class, STRING_TYPE_INFO, INT_TYPE_INFO, INT_TYPE_INFO, INT_TYPE_INFO))
      .writeAsText(config.getOutputDir(), WriteMode.OVERWRITE).setParallelism(1);
    env.execute();
  }


  private static void syserr(String title, Map definitions, int falseDefinitions, int correctDefinitions, int totalDefinitions, Set<IdentifierDefinition> tpDE, Set<IdentifierDefinition> fnDE, Set<IdentifierDefinition> fpDE, double dRecall, double dPrecision) {
    System.err.println("Summary " + title + " correct definitions: " + correctDefinitions + " incorrect definitions: " + falseDefinitions);
    System.err.println("total definitions: " + totalDefinitions + " correct definitions: " + correctDefinitions + " wrong definitions: " + falseDefinitions);
    System.err.println("Definition extraction precision: " + dPrecision + " recall: " + dRecall);
    System.err.println("tp: " + tpDE.size() + " fn: " + fnDE.size() + " fp: " + fpDE.size());
    System.err.println("Expected number of extracted definitions: " + definitions.keySet().size());
    System.err.println(title + "###################END#######################");
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
          //remove everything in brackets
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
