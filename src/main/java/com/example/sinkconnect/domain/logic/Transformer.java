package com.example.sinkconnect.domain.logic;

public interface Transformer<I,O> {

    O transform(I input);

}
