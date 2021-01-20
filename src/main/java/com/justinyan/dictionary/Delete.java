package com.justinyan.dictionary;

public class Delete extends Command {
  public String term;
  public String displayTerm;

  public Delete(String term, String displayTerm) {
    this.term = term;
    this.displayTerm = displayTerm;
  }
}
