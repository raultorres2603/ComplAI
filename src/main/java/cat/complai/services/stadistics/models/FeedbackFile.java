package cat.complai.services.stadistics.models;

import java.net.URL;

public class FeedbackFile {

    private String fileName;
    private URL url;

    public FeedbackFile(String fileName, URL url) {
        this.fileName = fileName;
        this.url = url;
    }

    public String getFileName() {
        return fileName;
    }

    public URL getUrl() {
        return url;
    }

}