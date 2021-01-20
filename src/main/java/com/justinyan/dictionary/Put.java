package com.justinyan.dictionary;

public class Put extends Command {
  public String term;
  public String displayTerm;
  public String definition;

  public Put(String term, String displayTerm, String definition) {
    this.term = term;
    this.displayTerm = displayTerm;
    this.definition = definition;
  }
}
