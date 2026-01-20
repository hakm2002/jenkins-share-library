// vars/goPipeline.groovy
def call(Map config = [:]) {
    def appName = config.appName ?: 'my-go-app'
    def projectDir = config.projectDir ?: '.' 
    def sonarCredId = config.sonarCredId ?: 'sonar-token'
    def dockerRegistry = config.dockerRegistry ?: 'docker.io'
    def imageTag = "latest"
    
    // Setup Docker User & Image Name
    def dockerUser = config.dockerUser ?: 'hakm2002' 
    def fullImageName = "${dockerUser}/${appName}" 

    pipeline {
        agent any

        environment {
            GO111MODULE = 'on'
            CGO_ENABLED = '0'
            PATH = "/usr/local/go/bin:${env.PATH}"
            DOCKER_CREDENTIAL_ID = 'dockerhub-id-hakm' 
        }

        stages {
            stage('Checkout Scm') {
                steps {
                    checkout scm
                }
            }

            stage('Unit Test & Coverage') {
                steps {
                    dir(projectDir) {
                        script {
                            echo "Running Unit Tests..."
                            sh 'go mod tidy'
                            // Output coverage.out akan muncul di dalam folder projectDir
                            sh 'go test ./... -coverprofile=coverage.out'
                        }
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    dir(projectDir) {
                        script {
                            echo "Starting SonarQube Analysis..."
                            def scannerHome = tool 'sonar-scanner'
                            
                            // Scanner akan otomatis mencari sonar-project.properties
                            // di dalam folder saat ini (projectDir)
                            withSonarQubeEnv('SonarQube') { 
                                sh "${scannerHome}/bin/sonar-scanner"
                            }
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Build Docker Image') {
                steps {
                    dir(projectDir) {
                        script {
                            echo "Building Docker Image: ${fullImageName}:${imageTag}"
                            // Pastikan Dockerfile ada di dalam projectDir
                            sh "docker build -t ${dockerRegistry}/${fullImageName}:${imageTag} ."
                        }
                    }
                }
            }

            stage('Push to Registry') {
                steps {
                    script {
                        echo "Pushing Image to Registry..."
                        withCredentials([usernamePassword(credentialsId: env.DOCKER_CREDENTIAL_ID, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                            sh "echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin"
                            sh "docker push ${dockerRegistry}/${fullImageName}:${imageTag}"
                        }
                    }
                }
            }
        }
        
        post {
            always {
                echo 'Pipeline finished.'
                cleanWs()
            }
            success {
                echo 'Build Success!'
            }
            failure {
                echo 'Build Failed :('
            }
        }
    }
}
