# 论文卡片：Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks

arXiv 编号：2005.11401 ｜ 年份：2020

作者：Patrick Lewis, Ethan Perez, Aleksandra Piktus, Fabio Petroni, Vladimir Karpukhin, Naman Goyal, Heinrich Küttler, Mike Lewis, Wen-tau Yih, Tim Rocktäschel, Sebastian Riedel, Douwe Kiela

## 摘要

大型预训练语言模型将事实知识存于参数中，在下游任务微调后可取得最先进结果，但访问与精确操纵知识的能力仍有限，在知识密集型任务上落后于专用架构，且决策溯源与知识更新是未解问题。本文探索检索增强生成（RAG）的通用微调方案：参数记忆是预训练 seq2seq 模型，非参数记忆是用神经检索器访问的维基百科稠密向量索引。比较了整段共享检索与逐 token 换段两种形式，在多个知识密集型任务上微调评估，在三个开放域问答任务上刷新最先进水平，生成的语言也比纯参数基线更具体、多样、符合事实。

## 备注

知识库检索链路的理论出处，写技术方案引用它。
