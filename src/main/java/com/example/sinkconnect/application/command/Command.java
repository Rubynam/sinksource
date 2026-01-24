package com.example.sinkconnect.application.command;

public interface Command<I,O> {

    O command(I input);
}
