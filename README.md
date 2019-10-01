ibroadcast-java-uploader
========================

This is an upload tool for the [iBroadcast music service](https://ibroadcast.com).

It's an derived and improved version of the Java uploader found at
[project.ibroadcast.com](https://project.ibroadcast.com/).

This is a personal private project. I do not work for and I'm not
affiliated with iBroadcast in any way.

**Disclaimer: This application and the information on this site is
for general information purposes only. You should not rely on this
application being correct, accurate or reliable. The application could
even damage your computer, trash your files or compromise your
iBroadcast account. iBroadcast has not reviewed this application,
nor is it aware of it. Any use of this application is therefore
strictly at your own risk!**

That being said, the application runs fine on _my_ computer and does
what it should, but your mileage may vary. 


First steps
-----------

For testing and development, create a free account at iBroadcast.com.

Then install Java from [jdk.java.net](https://jdk.java.net/).
You need **JDK 11** or higher.

Make sure that the `java` executable is in your PATH.

Then build the project (Linux/Mac):
```
./gradlew build
```
On Windows, use this instead:
```
.\gradlew.bat build
```

After the build was successful run the uploader as follows:
```
java -jar build/libs/ibroadcast-uploader.jar <email-address> <password> [<dir>]
```
where _dir_ is your top-level music folder you want to upload. _dir_
is optional. If you don't specify it, the _current_ folder gets used,
which is most likely _not_ what you want.

The application will scan this folder and all subfolders for supported
music files and upload it to iBroadcast.com.


How does it work
----------------

The basic steps are:

1. Log in with a email-address/password into iBroadcast.
2. Capture the supported file types from the server.
3. Use user_id and token for subsequent requests returned from initial
   request.
4. Get md5 listing of user's files (songs in their library) from
   iBroadcast server.
5. Find supported music files locally, give the user option to list
   those files found. Proceed when user confirms with 'U'.
6. Upload files to iBroadcast via http POST. \
   The files are uploaded in parallel with as many cores as your
   computer has. 
7. Skip songs already uploaded (via md5 compare).
