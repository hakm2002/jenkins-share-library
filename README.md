# Jenkins Shared Library for Go Projects

Library ini digunakan untuk standarisasi CI/CD project Golang.

## Cara Penggunaan
Tambahkan ini di paling atas `Jenkinsfile` project Anda:

```groovy
@Library('go-shared-library') _

goPipeline(
    appName: 'nama-aplikasi',
    projectDir: 'To-do-list',
    deployPort: '8082'
)