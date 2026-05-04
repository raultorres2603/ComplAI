package cat.complai.services.stadistics.models;

import java.net.URL;

public class ComplaintFile {

    private String fileName;
    private URL url;

    public ComplaintFile(String fileName, URL url) {
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
