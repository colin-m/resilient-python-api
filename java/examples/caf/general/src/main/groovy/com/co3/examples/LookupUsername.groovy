/*
 * Resilient Systems, Inc. ("Resilient") is willing to license software
 * or access to software to the company or entity that will be using or
 * accessing the software and documentation and that you represent as
 * an employee or authorized agent ("you" or "your") only on the condition
 * that you accept all of the terms of this license agreement.
 *
 * The software and documentation within Resilient's Development Kit are
 * copyrighted by and contain confidential information of Resilient. By
 * accessing and/or using this software and documentation, you agree that
 * while you may make derivative works of them, you:
 *
 * 1)  will not use the software and documentation or any derivative
 *     works for anything but your internal business purposes in
 *     conjunction your licensed used of Resilient's software, nor
 * 2)  provide or disclose the software and documentation or any
 *     derivative works to any third party.
 *
 * THIS SOFTWARE AND DOCUMENTATION IS PROVIDED "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL RESILIENT BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.co3.examples

import com.co3.domain.json.MethodName
import com.co3.dto.action.json.ActionDataDTO
import com.co3.dto.json.FullIncidentDataDTO
import com.co3.dto.json.IncidentArtifactDTO
import com.co3.dto.metadata.json.FieldRequired
import com.co3.dto.metadata.json.InputType
import com.co3.simpleclient.SimpleClient

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;


/**
 * Sample showing how you can call back into the Resilient REST API when responding to
 * Action Module messages.  
 * 
 * It reads from a message destination and if it finds a change to a user name
 * artifact, it tries to find the user name in the user-db.json JSON file.  If 
 * there is a match, it updates the artifact's description using the Resilient REST
 * API to include the user's name, title, manager and extension.
 * 
 * This sample uses the following files in your current working
 * directory:
 * 
 *  config.json  - The configuration settings needed to connect to the Resilient server.
 *                 See the config.json.dist file for what needs to be included in this file.
 *                
 *  user-db.json - Some test data used to fulfill the request (like a corporate LDAP directory).
 */
class LookupUsername {

    SimpleClient client
    String apiUrl
    String orgName
    String email
    String password
	File trustStoreFile
	String trustStorePassword
	
    SimpleClient getSimpleClient(String contextHeader = null) {
        if (!client) {
            client = new SimpleClient(new URL(apiUrl), orgName, email, password, trustStoreFile, trustStorePassword)
        }

        client.setContextHeader(contextHeader)

        try {
            client.getConstData()
        } catch (Exception e) {
            // hopefully just need to re-authenticate
            client.connect()
        }

        return client
    }

    ObjectMapper getObjectMapper() {
		return getSimpleClient().objectMapper
    }

    void processMessage(String messageText, String contextToken) {
        ActionDataDTO data = getObjectMapper().readValue(messageText, ActionDataDTO.class)

        if (data.artifact) {
            IncidentArtifactDTO artifact = data.artifact
            FullIncidentDataDTO incident = data.incident

            // hardcoded to username type
            if (artifact.type == 23) {
                Map userInfo = lookupUserInfo(artifact.value)

                artifact.description = "Name: ${userInfo['Name']}\n"
                artifact.description += "Title: ${userInfo['Title']}\n"
                artifact.description += "Manager: ${userInfo['Manager']}\n"
                artifact.description += "Extension: ${userInfo['Extension']}"

                println "Updating artifact description:\n${artifact.description}"

                SimpleClient client = getSimpleClient(contextToken)

                client.put("incidents/${incident.id}/artifacts/${artifact.id}", artifact, new TypeReference<IncidentArtifactDTO>() {})
                println "Updated artifact description"
            }
        }
    }

    Map lookupUserInfo(String username) {
        println "Looking up username ${username} in database"
        new File("user-db.json").withReader { reader ->
            def users = new JsonSlurper().parse(reader)

            def user = users[username]
            if (!user) {
                println "Did not find user"
                user = "No matching user found"
            } else {
                println "Found user"
            }
            return user
        }
    }

    static main(String[] args) {
        def config = new File("config.json")
        if (!config.exists()) {
            System.err.println("Config file ${config.name} does not exist")
            System.exit(1);
        }

        def slurper
        config.withReader { reader ->
            slurper = new JsonSlurper().parse(reader)
        }

		// Allow password to be specified in the config file, but if it's not then
		// just prompt the user.
        String password = slurper.password
		
		if (!password) {
			password = new String(System.console().readPassword("Please enter password: "))
		}
		
        DestinationWatcher watcher = new DestinationWatcher(slurper.email, password, slurper.jmsUrl)

		File trustStoreFile = slurper.trustStoreFile ? new File(slurper.trustStoreFile) : null
		String trustStorePassword = slurper.trustStorePassword
		
		if (trustStoreFile) {
			watcher.setTrustStoreInfo(trustStoreFile, trustStorePassword)
		}
		
        LookupUsername lu = new LookupUsername(apiUrl: slurper.apiUrl, 
			orgName: slurper.orgName, 
			email: slurper.email, 
			password: password,
			trustStoreFile: trustStoreFile,
			trustStorePassword: trustStorePassword)

        watcher.watch slurper.destinationName, Boolean.valueOf(slurper.topic), { String messageText, String contextToken ->
            lu.processMessage(messageText, contextToken)
        }
    }
}
