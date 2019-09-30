For testing and development, create a free account at iBroadcast.com. The
script is run in a parent directory (only search current directory and child
directories) and find all supported music files

The script works by:

java -jar dist/ibroadcast-uploader.jar <email_address> <password>

The basic steps are:

1. Log in with a username/password into iBroadcast 2. Capture the supported
file types from the server 3. Use user_id and token for subsequent requests
returned from initial request 4. Get md5 listing of user's files (songs in
their library) from iBroadcast server 5. Find supported music files locally,
give the user option to list those files found 6. Upload files to iBroadcast
via http/post 7. Skip songs already uploaded (via md5 compare)
 
This is an eclipse project. It can also be run from command line - both with and without ant build tool

Run using ant:
ant run -Dapplication.args="<email_address> <password>"

Jar using ant:
ant jar

Compile using ant:
ant compile

Run off jar (after compiling):
java -jar dist/ibroadcast-uploader.jar <email_address> <password>

Run off compiled classes (after compiling):
java -cp bin/classes iBroadcastUploader <email_address> <password>
