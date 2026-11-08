package com.huidu.musicboxplus.common.config;

public class GUIConfig {
    int rows = 6;
    String title = "<gold>GUI";
    String layout = "";
    int songAreaStartRow = 0;
    int songAreaEndRow = 5;
    int songAreaStartCol = 0;
    int songAreaEndCol = 9;
    ButtonMappingConfig buttonMapping = new ButtonMappingConfig();

    public int getRows() {
        return this.rows;
    }

    public String getTitle() {
        return this.title;
    }

    public String getLayout() {
        return this.layout;
    }

    public int getSongAreaStartRow() {
        return this.songAreaStartRow;
    }

    public int getSongAreaEndRow() {
        return this.songAreaEndRow;
    }

    public int getSongAreaStartCol() {
        return this.songAreaStartCol;
    }

    public int getSongAreaEndCol() {
        return this.songAreaEndCol;
    }

    public ButtonMappingConfig getButtonMapping() {
        return this.buttonMapping;
    }
}
