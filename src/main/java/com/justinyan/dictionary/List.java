package com.justinyan.dictionary;

import java.util.Optional;

public class List extends Command {
  public Optional<String> prefix;

  public List(String prefix) {
    this.prefix = Optional.ofNullable(prefix).map(String::toLowerCase);
  }
}
