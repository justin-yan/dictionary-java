package com.justinyan.dictionary;

public class Get extends Command {
  public String term;
  public String displayTerm;

  public Get(String term, String displayTerm) {
    this.term = term;
    this.displayTerm = displayTerm;
  }
}
