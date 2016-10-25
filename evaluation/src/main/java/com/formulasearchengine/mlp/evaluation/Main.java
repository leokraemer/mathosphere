package com.formulasearchengine.mlp.evaluation;

import java.io.IOException;

/**
 * Created by Leo on 25.10.2016.
 */
public class Main {
  public static void main(String[] args) throws IOException {
    CliParams params = CliParams.from(args);
    String command = params.getCommand();
    if (command == null) {
      params.printHelp();
    } else {
      switch (command) {
        case "eval":
          int[] result = (new Evaluator()).evaluate(params.getEvaluateCommand());
          System.out.println(String.format("tp: %d, fn: %d, fp: %d", result[0], result[1], result[2]));
          break;
        case "help":
        default:
          params.printHelp();
      }
    }
  }
}
