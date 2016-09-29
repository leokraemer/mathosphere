package com.formulasearchengine.mathosphere.mlp.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;

@Parameters(commandDescription = "Applies the MLP evaluation to an evaluation dataset")
public class EvalCommandConfig extends FlinkMlpCommandConfig implements Serializable {


  @Parameter(names = {"--queries"}, description = "query file")
  private String queries;


  @Parameter(names = {"--noExtract"}, description = "supply this option if the -in file was already annotated by the 'extract' task")
  private boolean noExtract;

  public String getQueries() {
    return queries;
  }

  public boolean getNoExtract() {
    return noExtract;
  }
}
