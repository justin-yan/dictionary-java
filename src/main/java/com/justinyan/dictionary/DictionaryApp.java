package com.justinyan.dictionary;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.slack.api.app_backend.slash_commands.response.SlashCommandResponse;
import com.slack.api.bolt.App;
import com.slack.api.model.block.DividerBlock;
import com.slack.api.model.block.HeaderBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.PlainTextObject;
import io.vavr.control.Try;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DictionaryApp {
  private static final Logger LOG = LoggerFactory.getLogger(DictionaryApp.class);
  private static final String HASH_KEY_NAME = "term";
  private static final String DISPLAY_NAME = "display_term";
  private static final String DEFINITION_NAME = "definition";

  public static String normalizeTerm(String term) {
    return term.toLowerCase();
  }

  public static Command parseCommand(String body) {
    LOG.error(body);
    Command command;
    if (body == null) {
      command = new List(null);
    } else {
      var split = body.split("=", 2);
      var word = split[0].strip();
      var term = normalizeTerm(word);
      var definition = Optional.ofNullable(split.length == 2 ? split[1] : null).map(String::strip);

      if (split.length == 1) {
        command = new Get(term, word);
      } else if (definition.map(String::isBlank).get()) {
        command = new Delete(term, word);
      } else {
        command = new Put(term, word, definition.get());
      }
    }
    return command;
  }

  public static SlashCommandResponse.SlashCommandResponseBuilder executeCommand(
      Command cmd, Table table) {
    SlashCommandResponse.SlashCommandResponseBuilder response;
    if (cmd instanceof List) {
      var l = (List) cmd;

      ScanSpec spec = new ScanSpec().withConsistentRead(true);
      ItemCollection<ScanOutcome> items = table.scan(spec);
      Iterator<Item> iterator = items.iterator();
      var stream =
          StreamSupport.stream(
              Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
      var relatedWords =
          stream
              .filter(
                  item ->
                      l.prefix
                          .map(prefix -> item.getString(HASH_KEY_NAME).startsWith(prefix))
                          .orElse(true)) // If no prefix, don't filter
              .sorted(Comparator.comparing((Item i) -> i.getString(HASH_KEY_NAME)))
              .map(item -> item.getString(DISPLAY_NAME))
              .collect(Collectors.toList());
      if (relatedWords.isEmpty()) {
        response = SlashCommandResponse.builder().text("There seem to be no words available.");
      } else {
        response = SlashCommandResponse.builder().text(String.join(", ", relatedWords));
      }
    } else if (cmd instanceof Get) {
      var g = (Get) cmd;
      var initialLookup =
          Try.of(
              () -> {
                var spec =
                    new GetItemSpec()
                        .withPrimaryKey(HASH_KEY_NAME, g.term)
                        .withConsistentRead(true);
                return Optional.ofNullable(table.getItem(spec));
              });
      initialLookup.onFailure(e -> LOG.error("Lookup failure:", e));

      if (initialLookup.isFailure() || initialLookup.get().isEmpty()) {
        LOG.error(String.format("Missing term: %s", g.term));
        // Perform recovery lookup.
        var intermediate = executeCommand(new List(g.term.substring(0, 1)), table);
        var blocks = new ArrayList<LayoutBlock>();
        blocks.add(
            HeaderBlock.builder()
                .text(
                    PlainTextObject.builder()
                        .text(
                            String.format(
                                "%s could not be found, we tried to look up similar words:",
                                g.displayTerm))
                        .build())
                .build());
        blocks.add(DividerBlock.builder().build());
        blocks.add(
            SectionBlock.builder()
                .text(PlainTextObject.builder().text(intermediate.build().getText()).build())
                .build());
        response = SlashCommandResponse.builder().blocks(blocks);
      } else {
        var responseItem = initialLookup.get().get();
        LOG.error(responseItem.toString());
        var responseDisplay = responseItem.getString(DISPLAY_NAME);
        var responseDef = responseItem.getString(DEFINITION_NAME);
        var blocks = new ArrayList<LayoutBlock>();
        blocks.add(
            HeaderBlock.builder()
                .text(PlainTextObject.builder().text(responseDisplay).build())
                .build());
        blocks.add(DividerBlock.builder().build());
        blocks.add(
            SectionBlock.builder()
                .text(
                    PlainTextObject.builder()
                        .text(String.format("Is defined as: %s", responseDef))
                        .build())
                .build());
        response = SlashCommandResponse.builder().blocks(blocks);
      }
    } else if (cmd instanceof Put) {
      var p = (Put) cmd;
      response =
          Try.of(
                  () -> {
                    var item =
                        new Item()
                            .withPrimaryKey(HASH_KEY_NAME, p.term)
                            .withString(DISPLAY_NAME, p.displayTerm)
                            .withString(DEFINITION_NAME, p.definition);
                    return table.putItem(item);
                  })
              .onFailure(e -> LOG.error("Error during term persistence:", e))
              .map(
                  outcome -> {
                    var blocks = new ArrayList<LayoutBlock>();
                    blocks.add(
                        HeaderBlock.builder()
                            .text(PlainTextObject.builder().text(p.displayTerm).build())
                            .build());
                    blocks.add(DividerBlock.builder().build());
                    blocks.add(
                        SectionBlock.builder()
                            .text(
                                PlainTextObject.builder()
                                    .text(String.format("Was saved as: %s", p.definition))
                                    .build())
                            .build());
                    return SlashCommandResponse.builder().blocks(blocks);
                  })
              .getOrElse(
                  SlashCommandResponse.builder()
                      .text("An error has occurred while attempting to define."));
    } else if (cmd instanceof Delete) {
      var d = (Delete) cmd;
      response =
          Try.of(() -> table.deleteItem(HASH_KEY_NAME, d.term))
              .map(
                  outcome ->
                      SlashCommandResponse.builder()
                          .text(String.format("%s has been deleted", d.displayTerm)))
              .getOrElse(
                  SlashCommandResponse.builder()
                      .text("An error has occurred while attempting to delete."));
    } else {
      throw new RuntimeException("Unknown Command");
    }
    return response;
  }

  public static App getApp() {
    var app = new App();

    app.command(
        "/define",
        (req, ctx) -> {
          var cmd = parseCommand(req.getPayload().getText());
          var dynamoTable = System.getenv("DYNAMODB_TABLE_NAME");
          var client = AmazonDynamoDBClientBuilder.standard().build();
          var dynamoDB = new DynamoDB(client);
          var table = dynamoDB.getTable(dynamoTable);
          ctx.respond(executeCommand(cmd, table).build());
          return ctx.ack();
        });
    return app;
  }
}
