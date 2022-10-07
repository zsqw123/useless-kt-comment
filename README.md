# useless-kt-comment
Find out what useless comments those colleagues have written / 找出那些同事写了相当于没写的注释

## Usage

### Sample


### Parameters
- `-file` (Required)
  - followed at the end by the file/folder you want to scan.
  - 后面加想要扫描的文件，可以是一个文件，也可以是个文件夹
- `-git`
  - The git repository where the scanned file is located, if not, no author information can be generated
  - 被扫描的文件所在的 git 仓库，如果没有，无法产生作者信息
- `-rate` Similarity ratio, default: 0.7

