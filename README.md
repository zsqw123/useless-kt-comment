# useless-kt-comment
Find out what useless comments those colleagues have written / 找出那些同事写了相当于没写的注释

## Usage
Detection of empty comments and some function naming itself can indicate what it is doing.

### Sample

File:  
```kotlin
package test

/**
 * bad
 */
class Bad {
    /** return single long method */
    fun singleLongMethod() = 1

    /***/
    fun noComment() = 2

    /** test for property */
    val testProperty =3
}
```

Output results:  
```
test.Bad#6: 0.72
  comment: /** return single long method */
  declaration: singleLongMethod
  author: zsu<i@mail.com>

test.Bad#9: 1.0
  comment: /***/
  declaration: noComment
  author: zsu<i@mail.com>

test.Bad#12: 0.7647058823529411
  comment: /** test for property */
  declaration: testProperty
  author: zsu<i@mail.com>

test.Bad#2: 1.0
  comment: /**  * bad  */
  declaration: Bad
  author: zsu<i@mail.com>
```

It will output the probability that the comment is useless, with a value from `0 to 1`, closer to 1 means that
the comment is more likely to be useless / 
它会输出可能是无用注释的可能性，取值为 0~1，越接近1代表注释越有可能是无用的

### Parameters

- `-file` (Required)
  - followed at the end by the file/folder you want to scan.
  - 后面加想要扫描的文件，可以是一个文件，也可以是个文件夹
- `-git`
  - The git repository where the scanned file is located, if not, no author information can be generated
  - 被扫描的文件所在的 git 仓库，如果没有，无法产生作者信息
- `-rate` Similarity ratio, default: 0.7
