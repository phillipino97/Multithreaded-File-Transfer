package IOThreads;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

//The class meant for checking the client's file list for relevant content
public class FileListUpdater {

    public static ArrayList<String> SearchForFile(String searchTerm) {
        ArrayList<String> fileNames = new ArrayList<>();
        //Checks to see if the uploads directory exists and if it does not then throw an error
        try {
            String currentPath = new java.io.File(".").getCanonicalPath().replace("\\", "/");
            File directory = new File(currentPath + "/uploads");
            if(!directory.exists()) {
                throw new IOException();
            }
            //Search the uploads directory
            File[] contents = directory.listFiles();
            if(contents != null) {
                for(File file : contents) {
                    //If the file is indeed a file and not a directory then search its lower case name with the lower case
                    //search term and if it contains the name then add it to the list
                    if(file.isFile()) {
                        if(file.getName().toLowerCase(Locale.ENGLISH).contains(searchTerm.toLowerCase())) {
                            fileNames.add(file.getName());
                        }
                    }
                }
            }
        } catch (IOException ignored) {

        }
        return fileNames;
    }
}
