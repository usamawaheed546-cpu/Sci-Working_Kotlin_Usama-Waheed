# searcher

A small grep-like tool written in Kotlin. It searches files and folders
recursively for a regular expression.

## Build

    ./gradlew installDist

The program is created in build/install/scientifictask/bin/scientifictask

## Usage

    scientifictask [OPTIONS] PATTERN [PATH ...]

    -A,--after-context <n>    print n lines after each match
    -B,--before-context <n>   print n lines before each match
    -C,--context <n>          print n lines before and after each match
    -c,--color                highlight the matches
    -h,--hidden               search hidden files and folders
    -i,--ignore-case          search case insensitive
       --no-heading           print the file name on every match line
       --help                 print this message

## Test

    python3 test.py --ripgrep -d <data-path> build/install/scientifictask/bin/scientifictask