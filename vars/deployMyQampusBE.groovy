def call(Map pipelineParams) {
  pipeline {
    agent {label 'master'}

    environment {
      GIT_BASE  = 'https://gitlab.com/qampus3/be/qampus-be/-/commit'
    }

    stages {
      stage('Source') {
        steps {
          sh "printenv | sort"
          sh "rm -rf * && rm -rf .git"
          git branch: "${params.branch}", url:  "git@gitlab.com:qampus3/be/qampus-be.git"
        }
      }

      stage('NOTIFY_SLACK_ABOUT_BUILD_STARTED') {
        steps {
          script {
            sh "ls -altr"
            env.GIT_COMMIT_MSG = sh (script: 'git log -1 --pretty=%B ${GIT_COMMIT}', returnStdout: true).trim()
            env.GIT_COMMIT_AUTHOR = sh (script: 'git log -1 --pretty=%cn ${GIT_COMMIT}', returnStdout: true).trim()
            env.GIT_COMMIT_HASH = sh (script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            echo "GIT_COMMIT_HASH: ${env.GIT_COMMIT_HASH}"
            echo "GIT_COMMIT_MSG: ${env.GIT_COMMIT_MSG}"
            echo "GIT_COMMIT_AUTHOR: ${env.GIT_COMMIT_AUTHOR}"
            echo "BUILD_URL: ${env.BUILD_URL}"
            slackSend (color: '#FFFF00', message: "${env.JOB_NAME} » ${env.branch} (<${env.GIT_BASE}/${env.GIT_COMMIT_HASH}|${env.GIT_COMMIT_HASH}>)  ```${env.GIT_COMMIT_MSG}``` » build (<${env.BUILD_URL}|${env.BUILD_NUMBER}>) » Started by user `${env.GIT_COMMIT_AUTHOR}`")
          }
        }
      }

      stage ("CREATE BUILD") {
        steps {
          script {
            docker.image('elixir:1.13.1').inside("--network=bridge -v /var/jenkins_home/workspace/qumpas-be:/root/qampus-be -v /var/run/docker.sock:/var/run/docker.sock") {
              sh "hostname"


              sh '''
                #!/bin/bash
                echo $BUILD_NUMBER
                ls -altr

                set -e

                # The one arg that we can accept will be the ENV we are building
                export MIX_ENV=prod

                echo "Installing rebar and hex..."
                mix local.rebar --force
                mix local.hex --if-missing --force

                echo "Fetching project deps..."
                mix deps.get --only prod

                echo "Cleaning any leftover artifacts..."
                mix do clean, compile --force

                echo "Generating release..."
                MIX_ENV=prod RELEASE_VERSION=$BUILD_NUMBER mix release qampus

                echo "Release generated!"

                ls -altr

                exit 0

              '''
            }

          }
        }
      }

      stage('DEPLOY') {
        steps {
          sshagent(['dev-MyQampus-server']) {
            sh "ls -altr"
            //sh "ssh -vvv -o StrictHostKeyChecking=no -T ubuntu@${pipelineParams.SERVER_IP}"

            sh 'ssh -o StrictHostKeyChecking=no -l ubuntu ${pipelineParams.SERVER_IP} /home/ubuntu/sites/${pipelineParams.SITE}/builds/bin/server stop &>/dev/null || true'
            sh 'scp  builds/server-$BUILD_NUMBER.tar.gz ubuntu@${pipelineParams.SERVER_IP}:/tmp'
            sh 'ssh -o StrictHostKeyChecking=no -l ubuntu ${pipelineParams.SERVER_IP} tar -xvzf /tmp/server-$BUILD_NUMBER.tar.gz --directory /home/ubuntu/sites/${pipelineParams.SITE}/builds'
            sh 'ssh -o StrictHostKeyChecking=no -l ubuntu ${pipelineParams.SERVER_IP} bash /home/ubuntu/sites/${pipelineParams.SITE}/deployment/be_start.sh'
          }
        }
      }

      stage('VALIDATE SERVER is RUNNING') {
        steps {
          script {
            // sleep for 30 seconds
            sleep 30
            // TODO: Below command doesnt makes sense
            sh '''
            #!/bin/bash
            echo > /dev/udp/${pipelineParams.SERVER_IP}/6001 && echo "Port is open"
            #nc -w 30 -v ${pipelineParams.SERVER_IP} 6001 </dev/null; echo $?
            '''
          }
        }
      }
    }
    post {
      always {
        script {
          if ( currentBuild.currentResult == "SUCCESS" ) {
            slackSend color: "good", message: "${env.JOB_NAME} » ${env.branch} (<${env.GIT_BASE}/${env.GIT_LAST_COMMIT}|${env.GIT_LAST_COMMIT}>)  configure context test cases » build (<${env.BUILD_URL}|${env.BUILD_NUMBER}>) » finish sucessfull"
          }
          else if( currentBuild.currentResult == "FAILURE" ) {
            slackSend color: "danger", message: "${env.JOB_NAME} » ${env.branch} (<${env.GIT_BASE}/${env.GIT_LAST_COMMIT}|${env.GIT_LAST_COMMIT}>)  configure context test cases » build (<${env.BUILD_URL}|${env.BUILD_NUMBER}>) » finish failure"
          }
          else if( currentBuild.currentResult == "UNSTABLE" ) {
            slackSend color: "danger", message: "${env.JOB_NAME} » ${env.branch} (<${env.GIT_BASE}/${env.GIT_LAST_COMMIT}|${env.GIT_LAST_COMMIT}>)  configure context test cases » build (<${env.BUILD_URL}|${env.BUILD_NUMBER}>) » finish unstable"
          }
          else {
            slackSend color: "danger", message: "${env.JOB_NAME} » ${env.branch} (<${env.GIT_BASE}/${env.GIT_LAST_COMMIT}|${env.GIT_LAST_COMMIT}>)  configure context test cases  » build (<${env.BUILD_URL}|${env.BUILD_NUMBER}>) » finish failure"
          }

          cleanWs()
        }
      }
    }
  }
}