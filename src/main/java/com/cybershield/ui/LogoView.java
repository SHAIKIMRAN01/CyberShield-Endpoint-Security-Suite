package com.cybershield.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.layout.StackPane;

/** The logo as a node FXML can use: {@code <LogoView size="104"/>}. */
public class LogoView extends StackPane {

    private final DoubleProperty size = new SimpleDoubleProperty(this, "size", 48);

    public LogoView() {

        getStyleClass().add("logo");

        size.addListener((observable, before, now) -> draw());

        draw();

    }

    private void draw() {

        getChildren().setAll(Logo.create(size.get()));

        setMinSize(size.get(), size.get());
        setPrefSize(size.get(), size.get());
        setMaxSize(size.get(), size.get());

    }

    public double getSize() {
        return size.get();
    }

    public void setSize(double value) {
        size.set(value);
    }

    public DoubleProperty sizeProperty() {
        return size;
    }

}
