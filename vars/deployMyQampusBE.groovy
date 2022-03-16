def call(Map pipelineParams) {
  pipeline {
    agent {label 'master'}

    environment {
      BUILD_START_SLACK_BAR_COLOR = '#FFFF00'
      APP_RELEASE_NAME = 'qampus'
      DOCKER_NETWORK_ARG = "--network=bridge -v /var/jenkins_home/workspace/qumpas-be:/root/qampus-be -v /var/run/docker.sock:/var/run/docker.sock"
      DOCKER_ELIXIR_IMAGE = "elixir:1.13.1"
      GIT_REPO_URL = "${pipelineParams.GIT_REPO_URL}"
      GIT_BRANCH = "${pipelineParams.GIT_BRANCH}"
      GIT_COMMIT_BASE_URL = "${pipelineParams.GIT_COMMIT_BASE_URL}"
      SERVER_IP = "${pipelineParams.SERVER_IP}"
      SITE = "${pipelineParams.SITE}"
    }

    stages {
      stage('Source') {
        steps {
          sh "printenv | sort"
          sh "rm -rf * && rm -rf .git"
          git branch: "${env.GIT_BRANCH}", url:  "${env.GIT_REPO_URL}"
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
            slackSend (color: "${env.BUILD_START_SLACK_BAR_COLOR}", message: "${env.JOB_NAME} » build (<${env.BUILD_URL}|${env.BUILD_NUMBER}>) » COMMIT » `${env.GIT_COMMIT_AUTHOR}` » ${env.branch} (<${env.GIT_COMMIT_BASE_URL}/${env.GIT_COMMIT_HASH}|${env.GIT_COMMIT_HASH}>)  ```${env.GIT_COMMIT_MSG}```")
          }
        }
      }

      stage ("CREATE BUILD") {
        steps {
          script {
            docker.image("${env.DOCKER_ELIXIR_IMAGE}").inside("${env.DOCKER_NETWORK_ARG}") {
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
                MIX_ENV=prod RELEASE_VERSION=$BUILD_NUMBER mix release ${env.APP_RELEASE_NAME}

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
          sshagent(['ssh-deploy-server']) {
            sh "ls -altr"
            //sh "ssh -vvv -o StrictHostKeyChecking=no -T ubuntu@${env.SERVER_IP}"

            sh 'ssh -o StrictHostKeyChecking=no -l ubuntu ${env.SERVER_IP} /home/ubuntu/sites/${env.SITE}/builds/bin/${env.APP_RELEASE_NAME} stop &>/dev/null || true'
            sh 'scp  builds/${env.APP_RELEASE_NAME}-$BUILD_NUMBER.tar.gz ubuntu@${env.SERVER_IP}:/tmp'
            sh 'ssh -o StrictHostKeyChecking=no -l ubuntu ${env.SERVER_IP} tar -xvzf /tmp/${env.APP_RELEASE_NAME}-$BUILD_NUMBER.tar.gz --directory /home/ubuntu/sites/${env.SITE}/builds'
            sh 'ssh -o StrictHostKeyChecking=no -l ubuntu ${env.SERVER_IP} bash /home/ubuntu/sites/${env.SITE}/deployment/be_start.sh'
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
            echo > /dev/udp/${env.SERVER_IP}/6001 && echo "Port is open"
            #nc -w 30 -v ${env.SERVER_IP} 6001 </dev/null; echo $?
            '''
          }
        }
      }
    }
    post {
      always {
        script {
          if ( currentBuild.currentResult == "SUCCESS" ) {
            slackSend color: "good", message: "${env.JOB_NAME} » ${env.branch} (<${env.GIT_COMMIT_BASE_URL}/${env.GIT_LAST_COMMIT}|${env.GIT_LAST_COMMIT}>)  configure context test cases » build (<${env.BUILD_URL}|${env.BUILD_NUMBER}>) » finish sucessfull"
          }
          else if( currentBuild.currentResult == "FAILURE" ) {
            slackSend color: "danger", message: "${env.JOB_NAME} » ${env.branch} (<${env.GIT_COMMIT_BASE_URL}/${env.GIT_LAST_COMMIT}|${env.GIT_LAST_COMMIT}>)  configure context test cases » build (<${env.BUILD_URL}|${env.BUILD_NUMBER}>) » finish failure"
          }
          else if( currentBuild.currentResult == "UNSTABLE" ) {
            slackSend color: "danger", message: "${env.JOB_NAME} » ${env.branch} (<${env.GIT_COMMIT_BASE_URL}/${env.GIT_LAST_COMMIT}|${env.GIT_LAST_COMMIT}>)  configure context test cases » build (<${env.BUILD_URL}|${env.BUILD_NUMBER}>) » finish unstable"
          }
          else {
            slackSend color: "danger", message: "${env.JOB_NAME} » ${env.branch} (<${env.GIT_COMMIT_BASE_URL}/${env.GIT_LAST_COMMIT}|${env.GIT_LAST_COMMIT}>)  configure context test cases  » build (<${env.BUILD_URL}|${env.BUILD_NUMBER}>) » finish failure"
          }

          cleanWs()
        }
      }
    }
  }
}